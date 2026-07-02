package com.cursormobile.data.net

import android.os.Build
import android.util.Log
import com.cursormobile.data.crypto.Crypto
import com.cursormobile.data.db.OutboxDao
import com.cursormobile.data.db.OutboxEntity
import com.cursormobile.data.prefs.AuthStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

enum class ConnState { Offline, Connecting, Online }

class RemoteException(message: String) : RuntimeException(message)

/**
 * The single, app-wide channel to the active paired Mac.
 *
 *  • Maintains a persistent WebSocket to the relay with auto-reconnect.
 *  • Encrypts outbound bodies, decrypts inbound.
 *  • Acks inbound, resumes from local seq on reconnect.
 *  • Exposes `incoming` as a SharedFlow of decoded envelopes.
 *  • Persists outbox so messages sent while offline replay on reconnect.
 */
@Singleton
class RemoteClient @Inject constructor(
    private val auth: AuthStore,
    private val outbox: OutboxDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            // Ktor 2.x supports either Duration or millis depending on version; setting
            // OkHttp's engine-level ping handles all builds reliably.
        }
        engine {
            preconfigured = okhttp3.OkHttpClient.Builder()
                .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(ConnState.Offline)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    // Large buffer + DROP_OLDEST so a slow collector never stalls the inbound pump.
    // Streaming chunks would otherwise back up and the WS reader would block.
    private val _incoming = MutableSharedFlow<Envelope>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val nextSeq = AtomicLong(1)
    @Volatile private var peerHighestSeq: Long = -1L
    @Volatile private var sharedKey: ByteArray? = null
    @Volatile private var sendCh: Channel<String>? = null
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runForever() }
    }

    fun stop() {
        loop?.cancel()
        scope.cancel()
        _state.value = ConnState.Offline
    }

    /** Derive the session key from the active pair; safe to call before each send. */
    private fun refreshSharedKey() {
        val pair = auth.activePair()
        val ourPriv = auth.ourPrivKey
        if (pair == null || ourPriv == null) {
            sharedKey = null
            return
        }
        sharedKey = runCatching { Crypto.deriveSharedKey(ourPriv, pair.macPubKey) }
            .getOrElse { e ->
                Log.e(TAG, "deriveSharedKey failed: ${e.message}")
                null
            }
    }

    private suspend fun runForever() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            val pair = auth.activePair()
            val relay = auth.relayUrl
            val ourPriv = auth.ourPrivKey
            val ourPub = auth.ourPubKey
            if (pair == null || relay == null || ourPriv == null || ourPub == null) {
                sharedKey = null
                _state.value = ConnState.Offline
                delay(2_000)
                continue
            }
            refreshSharedKey()
            _state.value = ConnState.Connecting
            try {
                connectOnce(relay, pair.pairId, ourPub)
                Log.i(TAG, "WS exited normally; reconnecting in ${backoffMs}ms")
                _state.value = ConnState.Connecting
                delay(backoffMs)
                backoffMs = 1_000L
            } catch (t: Throwable) {
                Log.w(TAG, "WS errored: ${t.message}; reconnecting in ${backoffMs}ms", t)
                _state.value = ConnState.Offline
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun connectOnce(relay: String, pairId: String, ourPub: String) {
        // Prefer LAN-direct when configured — sub-50ms typing latency vs ~150ms via relay.
        val lanHost = auth.lanHost
        val wsUrl = if (!lanHost.isNullOrBlank()) {
            "ws://${lanHost}:${auth.lanPort}/lan"
        } else {
            relay.replace("http", "ws").trimEnd('/') + "/pair/${pairId}/ws?role=phone"
        }
        Log.i(TAG, "WS connecting to $wsUrl")
        val session = client.webSocketSession { url(wsUrl) }
        try {
            _state.value = ConnState.Online
            Log.i(TAG, "WS open")
            val ch = Channel<String>(Channel.UNLIMITED)
            sendCh = ch

            // Handshake.
            val hello = Envelope(
                id = newId(), seq = 0, type = MessageTypes.HELLO, ts = now(),
                body = json.encodeToJsonElement(
                    HelloBody(
                        pairId = pairId,
                        pubKey = ourPub,
                        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        resumeFromSeq = peerHighestSeq,
                    )
                ),
            )
            session.send(Frame.Text(json.encodeToString(hello)))

            // Drain outbox into the channel first so it lands ahead of any new sends.
            // Only replay entries from the last 5 minutes — older requests are
            // almost certainly tied to dead UI coroutines and would re-trigger
            // duplicate work on the daemon for no observer.
            val freshCutoff = System.currentTimeMillis() - 5 * 60_000L
            outbox.pending(pairId, freshCutoff).forEach { ch.trySend(it.frameJson) }
            // GC: drop anything older than 1 hour so the table doesn't grow unbounded.
            outbox.pruneOlderThan(System.currentTimeMillis() - 60 * 60_000L)

            val outJob = scope.launch {
                for (frame in ch) {
                    runCatching { session.send(Frame.Text(frame)) }
                        .onFailure { ch.trySend(frame); return@launch }
                }
            }

            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                val env = try { json.decodeFromString<Envelope>(raw) } catch (t: Throwable) {
                    Log.w(TAG, "rx bad json: ${t.message}; raw=${raw.take(200)}")
                    continue
                }
                Log.i(TAG, "rx ${env.type} id=${env.id} ack=${env.ack}")
                if (env.seq > peerHighestSeq) peerHighestSeq = env.seq

                if (env.type !in setOf(MessageTypes.PING, MessageTypes.PONG, MessageTypes.ACK)) {
                    val ackEnv = Envelope(
                        id = newId(),
                        seq = nextSeq.getAndIncrement(),
                        type = MessageTypes.ACK,
                        ts = now(),
                        body = json.encodeToJsonElement(AckBody(listOf(env.id))),
                    )
                    runCatching { session.send(Frame.Text(json.encodeToString(ackEnv))) }
                }

                val decoded = if (env.type in MessageTypes.CONTROL) env else decryptBody(env)

                if (decoded.type == MessageTypes.ACK) {
                    val ids = decoded.body?.let { runCatching { json.decodeFromJsonElement(AckBody.serializer(), it).ids }.getOrNull() }
                    ids?.forEach { outbox.ack(it) }
                    // Daemon replies to IDE.SEND / IDE.WATCH with type=ACK and ack=<requestId>.
                    decoded.ack?.let { reqId -> pending.remove(reqId)?.complete(decoded) }
                    continue
                }

                // Complete any matching pending request first; the same envelope
                // also fans out to the SharedFlow for stream-style observers.
                val ack = decoded.ack
                if (ack != null) {
                    pending.remove(ack)?.complete(decoded)
                }
                _incoming.tryEmit(decoded)
            }
            outJob.cancel()
        } finally {
            sendCh = null
            runCatching { session.close() }
        }
    }

    suspend fun send(type: String, body: JsonElement?, ack: String? = null): String {
        refreshSharedKey()
        val pairId = auth.activePairId ?: throw IllegalStateException("no active pair")
        val maybeEncrypted = encryptIfNeeded(type, body, ack)
        val env = Envelope(
            id = newId(),
            seq = nextSeq.getAndIncrement(),
            ack = ack,
            type = type,
            ts = now(),
            body = maybeEncrypted,
        )
        val frame = json.encodeToString(env)
        outbox.enqueue(OutboxEntity(env.id, pairId, type, frame, now()))
        val ch = sendCh
        if (ch != null) {
            ch.trySend(frame)
            Log.i(TAG, "send ${env.type} id=${env.id} via live channel")
        } else {
            Log.i(TAG, "send ${env.type} id=${env.id} queued (no channel)")
        }
        return env.id
    }

    /**
     * Request/response pair: send a frame, wait for the daemon to ack it with
     * either the matching `okType` or an `ERROR` frame. Surfaces server-side
     * failures as exceptions instead of silently hanging.
     *
     * Optional [timeoutMs] guards against the daemon dying mid-request.
     */
    /**
     * Pre-registered pending requests, keyed by envelope id. The inbound pump
     * completes the matching deferred the moment the response arrives, so we
     * never lose a response to a SharedFlow subscription race.
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Envelope>>()

    suspend fun request(
        type: String,
        body: JsonElement?,
        okType: String,
        timeoutMs: Long = 45_000,
    ): Envelope {
        refreshSharedKey()
        // Register the slot BEFORE we know our id — but we need the id to key the map.
        // Trick: assign the id ourselves, register, then send with that id.
        val pairId = auth.activePairId ?: throw IllegalStateException("no active pair")
        val id = newId()
        val deferred = CompletableDeferred<Envelope>()
        pending[id] = deferred
        try {
            val maybeEncrypted = encryptIfNeeded(type, body, null)
            val env = Envelope(
                id = id,
                seq = nextSeq.getAndIncrement(),
                ack = null,
                type = type,
                ts = now(),
                body = maybeEncrypted,
            )
            val frame = json.encodeToString(env)
            outbox.enqueue(OutboxEntity(id, pairId, type, frame, now()))
            val ch = sendCh
            if (ch != null) {
                ch.trySend(frame)
                Log.i(TAG, "request ${type} id=${id} via live channel")
            } else {
                Log.i(TAG, "request ${type} id=${id} queued (no channel)")
            }
            val response = withTimeout(timeoutMs) { deferred.await() }
            if (response.type == MessageTypes.ERROR) {
                val errBody = response.body as? kotlinx.serialization.json.JsonObject
                val msgEl = errBody?.get("message") as? kotlinx.serialization.json.JsonPrimitive
                val msg = msgEl?.contentOrNull ?: "remote error"
                throw RemoteException(msg)
            }
            if (response.type != okType) {
                throw RemoteException("unexpected response type: ${response.type}")
            }
            return response
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            throw RemoteException("timed out waiting for $okType (${timeoutMs}ms)")
        } catch (e: RemoteException) {
            pending.remove(id)
            throw e
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
    }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = runCatching { content }.getOrNull()

    private fun encryptIfNeeded(type: String, body: JsonElement?, ack: String?): JsonElement? {
        if (type in MessageTypes.CONTROL || body == null) return body
        val key = sharedKey ?: return body
        val plaintext = json.encodeToString(JsonElement.serializer(), body).toByteArray()
        val sealed = Crypto.seal(key, plaintext, ("$type|${ack ?: ""}").toByteArray())
        return json.encodeToJsonElement(SealedBody.serializer(), SealedBody(sealed.nonceB64, sealed.ciphertextB64))
    }

    private fun isSealedBody(body: JsonElement): Boolean {
        val obj = body as? kotlinx.serialization.json.JsonObject ?: return false
        return obj.containsKey("n") && obj.containsKey("c")
    }

    private fun decryptBody(env: Envelope): Envelope {
        val key = sharedKey ?: return env.also { Log.w(TAG, "no shared key, passing through ${env.type}") }
        val body = env.body ?: return env
        if (!isSealedBody(body)) return env
        return try {
            val sealed = json.decodeFromJsonElement(SealedBody.serializer(), body)
            val plainBytes = Crypto.open(key, Crypto.Sealed(sealed.n, sealed.c), ("${env.type}|${env.ack ?: ""}").toByteArray())
            env.copy(body = json.parseToJsonElement(plainBytes.toString(Charsets.UTF_8)))
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt failed for ${env.type} ack=${env.ack}: ${t.message}")
            env
        }
    }

    private fun newId(): String {
        val ts = System.currentTimeMillis().toString(16).padStart(12, '0')
        val r = (0 until 8).joinToString("") { "%02x".format((0..255).random()) }
        return ts + r
    }
    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "CursorMobile.WS"
    }
}
