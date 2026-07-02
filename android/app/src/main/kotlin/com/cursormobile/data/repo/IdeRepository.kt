package com.cursormobile.data.repo

import com.cursormobile.data.net.IdeComposerDetail
import com.cursormobile.data.net.IdeComposerMessage
import com.cursormobile.data.net.IdeGetOk
import com.cursormobile.data.net.IdeListOk
import com.cursormobile.data.net.IdeRunResultBody
import com.cursormobile.data.net.IdeStreamBody
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.RemoteClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class IdeRepository @Inject constructor(private val client: RemoteClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json get() = client.json

    private val _bridgeReady = MutableStateFlow(false)
    val bridgeReady: StateFlow<Boolean> = _bridgeReady.asStateFlow()

    private val _streamEvents = MutableSharedFlow<IdeStreamBody>(extraBufferCapacity = 512)
    val streamEvents: SharedFlow<IdeStreamBody> = _streamEvents.asSharedFlow()

    private val _runResults = MutableSharedFlow<IdeRunResultBody>(extraBufferCapacity = 32)
    val runResults: SharedFlow<IdeRunResultBody> = _runResults.asSharedFlow()

    init {
        scope.launch { collectPushEvents() }
    }

    suspend fun list(limit: Int = 200): IdeListOk {
        val env = client.request(
            MessageTypes.IDE_LIST,
            buildJsonObject { put("limit", limit) },
            MessageTypes.IDE_LIST_OK,
            timeoutMs = 60_000,
        )
        val result = json.decodeFromJsonElement(IdeListOk.serializer(), env.body!!)
        _bridgeReady.value = result.bridgeReady
        return result
    }

    suspend fun get(id: String, tail: Int = 120): IdeGetOk {
        val env = client.request(
            MessageTypes.IDE_GET,
            buildJsonObject {
                put("id", id)
                put("tail", tail)
            },
            MessageTypes.IDE_GET_OK,
            timeoutMs = 20_000,
        )
        return json.decodeFromJsonElement(IdeGetOk.serializer(), env.body!!)
    }

    suspend fun send(composerId: String, prompt: String): String {
        val runId = (1..16).joinToString("") { (0..15).random().toString(16) }
        client.send(
            MessageTypes.IDE_SEND,
            buildJsonObject {
                put("composerId", composerId)
                put("prompt", prompt)
                put("runId", runId)
            },
        )
        return runId
    }

    suspend fun cancel(composerId: String, runId: String? = null) {
        client.request(
            MessageTypes.IDE_CANCEL,
            buildJsonObject {
                put("composerId", composerId)
                if (runId != null) put("runId", runId)
            },
            MessageTypes.ACK,
            timeoutMs = 5_000,
        )
    }

    suspend fun watch(composerId: String) {
        client.send(
            MessageTypes.IDE_WATCH,
            buildJsonObject {
                put("composerId", composerId)
                put("runId", "live-${System.currentTimeMillis()}")
            },
        )
    }

    suspend fun unwatch(composerId: String) {
        runCatching {
            client.request(
                MessageTypes.IDE_UNWATCH,
                buildJsonObject { put("composerId", composerId) },
                MessageTypes.ACK,
                timeoutMs = 5_000,
            )
        }
    }

    private suspend fun collectPushEvents() {
        client.incoming.collect { env ->
            when (env.type) {
                MessageTypes.IDE_STREAM -> {
                    val body = runCatching {
                        json.decodeFromJsonElement(IdeStreamBody.serializer(), env.body!!)
                    }.getOrNull() ?: return@collect
                    _streamEvents.emit(body)
                }
                MessageTypes.IDE_RUN_RESULT -> {
                    val body = runCatching {
                        json.decodeFromJsonElement(IdeRunResultBody.serializer(), env.body!!)
                    }.getOrNull() ?: return@collect
                    _runResults.emit(body)
                }
            }
        }
    }
}

/** Apply streaming events to an in-memory composer detail snapshot. */
fun IdeComposerDetail.applyStream(event: IdeStreamBody): IdeComposerDetail {
    if (event.composerId != id) return this
    val msgs = messages.toMutableList()
    when (event.event.type) {
        "status" -> {
            val generating = event.event.status == "generating"
            if (!generating) {
                for (i in msgs.indices) {
                    if (msgs[i].role == "assistant" && msgs[i].isStreaming) {
                        msgs[i] = msgs[i].copy(isStreaming = false)
                    }
                }
            }
            if (msgs.isNotEmpty()) {
                val last = msgs.last()
                if (last.role == "assistant") {
                    msgs[msgs.lastIndex] = last.copy(isStreaming = generating)
                }
            }
            return copy(messages = msgs, status = if (generating) "generating" else "idle")
        }
        "user" -> {
            val bubbleId = event.event.bubbleId ?: return this
            val text = event.event.text ?: ""
            val pendingIdx = msgs.indexOfLast { it.role == "user" && it.bubbleId.startsWith("pending-") }
            if (pendingIdx >= 0) {
                msgs[pendingIdx] = msgs[pendingIdx].copy(bubbleId = bubbleId, text = text)
            } else if (msgs.none { it.bubbleId == bubbleId }) {
                msgs.add(IdeComposerMessage(bubbleId = bubbleId, role = "user", text = text))
            }
        }
        "assistant_delta" -> {
            val bubbleId = event.event.bubbleId ?: return this
            val text = event.event.text ?: ""
            val streaming = event.event.isStreaming == true
            val idx = msgs.indexOfFirst { it.bubbleId == bubbleId }
            if (idx >= 0) {
                msgs[idx] = msgs[idx].copy(text = text, isStreaming = streaming)
            } else {
                msgs.add(
                    IdeComposerMessage(
                        bubbleId = bubbleId,
                        role = "assistant",
                        text = text,
                        isStreaming = streaming,
                    ),
                )
            }
        }
        "tool" -> {
            val bubbleId = event.event.bubbleId ?: return this
            val idx = msgs.indexOfFirst { it.bubbleId == bubbleId }
            val toolMsg = IdeComposerMessage(
                bubbleId = bubbleId,
                role = "tool",
                text = event.event.text ?: "",
                toolName = event.event.toolName,
                toolInput = event.event.toolInput,
                toolOutput = event.event.toolOutput,
                toolStatus = event.event.toolStatus,
            )
            if (idx >= 0) msgs[idx] = toolMsg else msgs.add(toolMsg)
        }
    }
    return copy(messages = msgs, bubbleCount = maxOf(bubbleCount, msgs.size))
}
