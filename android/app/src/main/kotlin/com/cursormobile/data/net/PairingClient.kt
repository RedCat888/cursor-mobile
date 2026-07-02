package com.cursormobile.data.net

import android.os.Build
import android.util.Base64
import com.cursormobile.data.crypto.Crypto
import com.cursormobile.data.db.OutboxDao
import com.cursormobile.data.prefs.AuthStore
import com.cursormobile.data.prefs.PairedMac
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the phone-side pairing handshake against the relay.
 *
 * The Mac initiates pairing via `POST /pair/start` (CLI), then opens its own
 * WS as role=mac. We decode the QR payload, open WS as role=phone, send
 * HELLO + PAIR.CLAIM, and persist the resulting peer pubkey on PAIR.OK.
 */
@Serializable
data class PairQrPayload(
    val v: Int = 1,
    val relay: String,
    val pairId: String,
    val code: String,
    val fp: String,
    val label: String,
)

@Singleton
class PairingClient @Inject constructor(
    private val auth: AuthStore,
    private val outbox: OutboxDao,
) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parseQr(text: String): PairQrPayload? {
        return try {
            val payload = if (text.startsWith("cm1://pair#")) text.substringAfter("#") else text
            val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                .toString(Charsets.UTF_8)
            json.decodeFromString<PairQrPayload>(decoded)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun pair(qr: PairQrPayload): PairedMac {
        // Ensure we have a stable identity keypair.
        if (auth.ourPubKey == null || auth.ourPrivKey == null) {
            val kp = Crypto.generateKeyPair()
            auth.ourPubKey = kp.publicKeyB64
            auth.ourPrivKey = kp.privateKeyB64
        }
        auth.relayUrl = qr.relay

        val wsUrl = qr.relay.replace("http", "ws").trimEnd('/') + "/pair/${qr.pairId}/ws?role=phone"
        val session = client.webSocketSession { url(wsUrl) }

        try {
            session.send(Frame.Text(json.encodeToString(
                Envelope(
                    id = newId(), seq = 0, type = MessageTypes.HELLO, ts = now(),
                    body = json.encodeToJsonElement(
                        HelloBody(
                            pairId = qr.pairId,
                            pubKey = auth.ourPubKey!!,
                            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        )
                    ),
                )
            )))
            session.send(Frame.Text(json.encodeToString(
                Envelope(
                    id = newId(), seq = 0, type = MessageTypes.PAIR_CLAIM, ts = now(),
                    body = json.encodeToJsonElement(
                        PairClaimBody(
                            code = qr.code,
                            pubKey = auth.ourPubKey!!,
                            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
                        )
                    ),
                )
            )))

            val pair = withTimeout(30_000) {
                var result: PairedMac? = null
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val env = json.decodeFromString<Envelope>(frame.readText())
                    if (env.type == MessageTypes.PAIR_OK) {
                        val obj = env.body as? JsonObject ?: continue
                        val peerPub = obj["peerPubKey"]?.jsonPrimitive?.contentOrNullSafe()
                        val peerLabel = obj["peerLabel"]?.jsonPrimitive?.contentOrNullSafe()
                        if (peerPub != null) {
                            result = PairedMac(qr.pairId, peerPub, peerLabel ?: qr.label, System.currentTimeMillis())
                            break
                        }
                    } else if (env.type == MessageTypes.ERROR) {
                        val msg = (env.body as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNullSafe() ?: "pair failed"
                        throw IllegalStateException(msg)
                    }
                }
                result ?: throw IllegalStateException("pair: handshake ended without PAIR.OK")
            }

            auth.savePair(pair)
            auth.activePairId = pair.pairId
            outbox.clearAll()
            return pair
        } finally {
            runCatching { session.close() }
        }
    }

    private fun newId(): String {
        val ts = System.currentTimeMillis().toString(16).padStart(12, '0')
        val r = (0 until 8).joinToString("") { "%02x".format((0..255).random()) }
        return ts + r
    }
    private fun now() = System.currentTimeMillis()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
