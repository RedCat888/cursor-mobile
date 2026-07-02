package com.cursormobile.data.repo

import com.cursormobile.data.db.AgentDao
import com.cursormobile.data.db.AgentEntity
import com.cursormobile.data.db.MessageDao
import com.cursormobile.data.db.MessageEntity
import com.cursormobile.data.net.AgentChatMessage
import com.cursormobile.data.net.AgentGetResult
import com.cursormobile.data.net.AgentListResult
import com.cursormobile.data.net.AgentRunResultBody
import com.cursormobile.data.net.AgentStreamBody
import com.cursormobile.data.net.AgentSummary
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.ModelDescriptor
import com.cursormobile.data.net.ModelsResult
import com.cursormobile.data.net.RemoteClient
import com.cursormobile.data.prefs.AuthStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class AgentsRepository @Inject constructor(
    private val client: RemoteClient,
    private val auth: AuthStore,
    private val agentDao: AgentDao,
    private val messageDao: MessageDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()
    private val json get() = client.json

    init {
        scope.launch { collectStreams() }
    }

    fun observeAgents(): Flow<List<AgentEntity>> {
        val pid = auth.activePairId ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return agentDao.observe(pid)
    }

    fun observeAgent(id: String) = agentDao.observeOne(id)
    fun observeMessages(agentId: String) = messageDao.observe(agentId)

    suspend fun reapStalePending(cutoff: Long) = messageDao.reapStalePending(cutoff)

    suspend fun refreshList(runtime: String = "all") {
        val pid = auth.activePairId ?: return
        val env = client.request(
            MessageTypes.AGENT_LIST,
            buildJsonObject { put("runtime", runtime) },
            MessageTypes.AGENT_LIST_RESULT,
            timeoutMs = 60_000,
        )
        val result = json.decodeFromJsonElement(AgentListResult.serializer(), env.body!!)
        agentDao.upsertAll(result.agents.map { it.toEntity(pid) })
    }

    suspend fun loadModels() {
        val env = client.request(MessageTypes.AGENT_MODELS, buildJsonObject {}, MessageTypes.AGENT_MODELS_RESULT)
        _models.value = json.decodeFromJsonElement(ModelsResult.serializer(), env.body!!).models
    }

    suspend fun create(opts: AgentCreateOpts): AgentSummary {
        val env = client.request(
            MessageTypes.AGENT_CREATE,
            buildJsonObject {
                opts.cwd?.let { put("cwd", it) }
                put("model", opts.model)
                put("runtime", opts.runtime)
                opts.title?.let { put("title", it) }
                opts.initialPrompt?.let { put("initialPrompt", it) }
            },
            MessageTypes.AGENT_CREATE_RESULT,
        )
        val summary = json.decodeFromJsonElement(AgentSummary.serializer(), env.body!!)
        val entity = summary.toEntity(auth.activePairId!!).copy(title = opts.title ?: summary.title)
        agentDao.upsert(entity)
        return summary.copy(title = entity.title)
    }

    suspend fun send(agentId: String, prompt: String) {
        val runId = (1..16).joinToString("") { (0..15).random().toString(16) }
        messageDao.insert(MessageEntity(agentId = agentId, runId = runId, role = "user", text = prompt, ts = System.currentTimeMillis()))
        val assistantId = messageDao.insert(
            MessageEntity(agentId = agentId, runId = runId, role = "assistant", text = "", ts = System.currentTimeMillis(), pending = true),
        )
        pendingAssistantByRun[runId] = assistantId
        client.send(MessageTypes.AGENT_SEND, buildJsonObject {
            put("agentId", agentId); put("prompt", prompt); put("runId", runId)
        })
    }

    suspend fun cancel(agentId: String, runId: String) {
        client.send(MessageTypes.AGENT_CANCEL, buildJsonObject {
            put("agentId", agentId); put("runId", runId)
        })
    }

    suspend fun setModel(agentId: String, model: String) {
        val env = client.request(
            MessageTypes.AGENT_SET_MODEL,
            buildJsonObject { put("agentId", agentId); put("model", model) },
            MessageTypes.AGENT_SET_MODEL_RESULT,
        )
        val summary = json.decodeFromJsonElement(AgentSummary.serializer(), env.body!!)
        auth.activePairId?.let { pid -> agentDao.upsert(summary.toEntity(pid)) }
    }

    suspend fun openInDetail(agentId: String): AgentGetResult? {
        return runCatching {
            val env = client.request(
                MessageTypes.AGENT_GET,
                buildJsonObject { put("agentId", agentId) },
                MessageTypes.AGENT_GET_RESULT,
            )
            val result = json.decodeFromJsonElement(AgentGetResult.serializer(), env.body!!)
            hydrate(agentId, result)
            result
        }.getOrNull()
    }

    private suspend fun hydrate(agentId: String, result: AgentGetResult) {
        val pid = auth.activePairId ?: return
        agentDao.upsert(result.agent.toEntity(pid))
        if (messageDao.countPending(agentId) > 0) return
        messageDao.deleteNonPending(agentId)
        for (msg in result.messages) {
            messageDao.insert(msg.toEntity(agentId))
        }
    }

    private val pendingAssistantByRun = HashMap<String, Long>()
    private val seenToolCalls = HashSet<String>()

    private suspend fun collectStreams() {
        client.incoming.collect { env ->
            when (env.type) {
                MessageTypes.AGENT_STREAM -> {
                    val body = runCatching { json.decodeFromJsonElement(AgentStreamBody.serializer(), env.body!!) }.getOrNull() ?: return@collect
                    handleStream(body)
                }
                MessageTypes.AGENT_RUN_RESULT -> {
                    val body = runCatching { json.decodeFromJsonElement(AgentRunResultBody.serializer(), env.body!!) }.getOrNull() ?: return@collect
                    when (body.status) {
                        "running" -> Unit // early ack — ignore
                        "finished" -> {
                            val id = pendingAssistantByRun.remove(body.runId) ?: return@collect
                            messageDao.finalizePending(id, body.summary)
                        }
                        "error", "start_error", "cancelled" -> {
                            val id = pendingAssistantByRun.remove(body.runId) ?: return@collect
                            messageDao.finalizePending(id, body.summary ?: body.status)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleStream(b: AgentStreamBody) {
        val eventObj = b.event as? JsonObject ?: return
        val eventType = eventObj["type"]?.jsonPrimitive?.contentOrNull() ?: return
        when (eventType) {
            "assistant" -> {
                val msg = eventObj["message"] as? JsonObject ?: return
                val contentArr = (msg["content"] as? JsonArray) ?: return
                val text = StringBuilder()
                for (block in contentArr) {
                    val blk = block as? JsonObject ?: continue
                    when (blk["type"]?.jsonPrimitive?.contentOrNull()) {
                        "text" -> text.append(blk["text"]?.jsonPrimitive?.contentOrNull() ?: "")
                        "tool_use" -> {
                            val callId = blk["id"]?.jsonPrimitive?.contentOrNull() ?: return@handleStream
                            val dedupeKey = "${b.agentId}:$callId"
                            if (seenToolCalls.add(dedupeKey)) {
                                insertToolMessage(
                                    agentId = b.agentId,
                                    runId = b.runId,
                                    callId = callId,
                                    name = blk["name"]?.jsonPrimitive?.contentOrNull() ?: "tool",
                                    input = blk["input"],
                                    status = "running",
                                )
                            }
                        }
                    }
                }
                if (text.isNotEmpty()) {
                    val id = pendingAssistantByRun[b.runId] ?: return
                    messageDao.appendChunk(id, text.toString())
                }
            }
            "thinking" -> {
                val msg = eventObj["message"] as? JsonObject
                val txt = msg?.get("thinking")?.jsonPrimitive?.contentOrNull()
                    ?: msg?.get("text")?.jsonPrimitive?.contentOrNull()
                    ?: eventObj["text"]?.jsonPrimitive?.contentOrNull()
                    ?: return
                messageDao.insert(
                    MessageEntity(
                        agentId = b.agentId, runId = b.runId,
                        role = "thinking", text = txt,
                        ts = System.currentTimeMillis(),
                    ),
                )
            }
            "tool_use", "tool_call" -> {
                val callId = eventObj["call_id"]?.jsonPrimitive?.contentOrNull()
                    ?: eventObj["id"]?.jsonPrimitive?.contentOrNull()
                    ?: return
                val dedupeKey = "${b.agentId}:$callId"
                val name = eventObj["name"]?.jsonPrimitive?.contentOrNull() ?: "tool"
                val status = eventObj["status"]?.jsonPrimitive?.contentOrNull() ?: "running"
                val input = eventObj["args"] ?: eventObj["input"]
                val result = eventObj["result"]
                val existing = messageDao.findToolMessageId(b.agentId, callId)
                if (existing != null) {
                    messageDao.updateTool(
                        id = existing,
                        text = name,
                        input = input?.toString(),
                        output = result?.toString(),
                        status = status,
                    )
                } else if (seenToolCalls.add(dedupeKey)) {
                    insertToolMessage(b.agentId, b.runId, callId, name, input, status, result?.toString())
                }
            }
            "status" -> Unit
        }
    }

    private suspend fun insertToolMessage(
        agentId: String,
        runId: String?,
        callId: String,
        name: String,
        input: kotlinx.serialization.json.JsonElement?,
        status: String,
        output: String? = null,
    ) {
        messageDao.insert(
            MessageEntity(
                agentId = agentId,
                runId = runId,
                role = "tool",
                text = name,
                toolName = name,
                toolInput = input?.toString(),
                toolOutput = output,
                toolId = callId,
                toolStatus = status,
                ts = System.currentTimeMillis(),
            ),
        )
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    private fun AgentSummary.toEntity(pid: String) = AgentEntity(
        agentId = agentId,
        pairId = pid,
        runtime = runtime,
        model = model,
        cwd = cwd,
        title = title,
        status = status,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
    )

    private fun com.cursormobile.data.net.AgentDetail.toEntity(pid: String) = AgentEntity(
        agentId = agentId,
        pairId = pid,
        runtime = runtime,
        model = model,
        cwd = cwd,
        title = title,
        status = status,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
    )

    private fun AgentChatMessage.toEntity(agentId: String) = MessageEntity(
        agentId = agentId,
        runId = null,
        role = role,
        text = text,
        toolName = toolName,
        toolInput = toolInput,
        toolOutput = toolOutput,
        toolId = toolId,
        toolStatus = toolStatus,
        remoteId = id,
        ts = ts,
    )
}

data class AgentCreateOpts(
    val cwd: String?,
    val model: String,
    val runtime: String,
    val title: String? = null,
    val initialPrompt: String?,
)
