package com.cursormobile.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types — must stay in sync with `mac-daemon/src/protocol.ts`.
 * The body is intentionally untyped at this layer; service repos cast.
 */
@Serializable
data class Envelope(
    val v: Int = 1,
    val id: String,
    val seq: Long,
    val ack: String? = null,
    val type: String,
    val ts: Long,
    val body: JsonElement? = null,
)

@Serializable
data class SealedBody(val n: String, val c: String)

@Serializable
data class HelloBody(
    val role: String = "phone",
    val pairId: String,
    val pubKey: String,
    val deviceLabel: String,
    val resumeFromSeq: Long? = null,
)

@Serializable
data class HelloOkBody(
    val peerOnline: Boolean,
    val peerPubKey: String? = null,
    val queuedCount: Int = 0,
    val paired: Boolean = false,
)

@Serializable
data class PairClaimBody(
    val code: String,
    val pubKey: String,
    val deviceLabel: String,
)

@Serializable
data class PairOkBody(
    val peerPubKey: String? = null,
    val peerLabel: String? = null,
    val paired: Boolean = false,
)

@Serializable
data class AckBody(val ids: List<String>)

@Serializable
data class ErrorBody(val code: String, val message: String, val retryable: Boolean? = null)

@Serializable
data class AgentSummary(
    val agentId: String,
    val runtime: String,
    val model: String,
    val cwd: String? = null,
    val repos: List<String>? = null,
    val createdAt: Long,
    val lastActivityAt: Long,
    val status: String,
    val title: String? = null,
)

@Serializable
data class AgentDetail(
    val agentId: String,
    val runtime: String,
    val model: String,
    val cwd: String? = null,
    val repos: List<String>? = null,
    val createdAt: Long,
    val lastActivityAt: Long,
    val status: String,
    val title: String? = null,
    val mcpServers: List<String>? = null,
)

@Serializable
data class AgentListResult(val agents: List<AgentSummary>)

@Serializable
data class AgentGetResult(
    val agent: AgentDetail,
    val recentRuns: List<RunSummary>,
    val messages: List<AgentChatMessage> = emptyList(),
)

@Serializable
data class AgentChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolId: String? = null,
    val toolStatus: String? = null,
    val ts: Long,
)

@Serializable
data class RunSummary(
    val runId: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val summary: String? = null,
)

@Serializable
data class AgentStreamBody(val agentId: String, val runId: String, val event: JsonElement)

@Serializable
data class AgentRunResultBody(
    val agentId: String,
    val runId: String,
    val status: String,
    val summary: String? = null,
)

@Serializable
data class ModelDescriptor(
    val id: String,
    val displayName: String? = null,
    val supportsReasoning: Boolean = false,
    val maxMode: Boolean = false,
)

@Serializable
data class ModelsResult(val models: List<ModelDescriptor>)

@Serializable
data class FsEntry(val name: String, val kind: String, val size: Long, val mtime: Long)

@Serializable
data class FsListBody(val path: String)

@Serializable
data class FsListOk(val path: String, val entries: List<FsEntry>)

@Serializable
data class FsReadBody(val path: String, val maxBytes: Int? = null)

@Serializable
data class FsReadOk(val path: String, val contentB64: String, val encoding: String, val truncated: Boolean)

@Serializable
data class FsWriteBody(val path: String, val contentB64: String)

@Serializable
data class FsWriteOk(val path: String, val bytes: Long)

@Serializable
data class FsWorkspacesOk(
    val recent: List<FsRecentWorkspace> = emptyList(),
    val allowed: List<String> = emptyList(),
    val home: String,
)

@Serializable
data class FsRecentWorkspace(val path: String, val lastOpened: Long? = null)

@Serializable
data class PtyOpenBody(val cwd: String? = null, val cols: Int = 100, val rows: Int = 30, val shell: String? = null)

@Serializable
data class PtyOpenedBody(val ptyId: String, val pid: Int, val shell: String, val cwd: String)

@Serializable
data class PtyStdinBody(val ptyId: String, val dataB64: String)

@Serializable
data class PtyStdoutBody(val ptyId: String, val dataB64: String, val seq: Long)

@Serializable
data class PtyResizeBody(val ptyId: String, val cols: Int, val rows: Int)

@Serializable
data class PtyCloseBody(val ptyId: String)

@Serializable
data class PtyExitBody(val ptyId: String, val code: Int)

@Serializable
data class PtySession(val ptyId: String, val cwd: String, val shell: String, val lastSeq: Long, val bufferPreview: String, val exited: Boolean = false)

@Serializable
data class PtyListOk(val sessions: List<PtySession>)

@Serializable
data class PtyAttachBody(val ptyId: String, val sinceSeq: Long)

@Serializable
data class McpServer(
    val name: String,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val url: String? = null,
    val type: String? = null,
    val headers: Map<String, String>? = null,
    val enabled: Boolean = true,
)

@Serializable
data class McpListOk(val user: List<McpServer> = emptyList(), val project: Map<String, List<McpServer>> = emptyMap())

@Serializable
data class McpUpsertBody(val scope: String, val projectPath: String? = null, val server: McpServer)

@Serializable
data class McpDeleteBody(val scope: String, val projectPath: String? = null, val name: String)

@Serializable
data class McpToggleBody(val scope: String, val projectPath: String? = null, val name: String, val enabled: Boolean)

@Serializable
data class McpMutationOk(val servers: List<McpServer>)

@Serializable
data class IdeComposerSummary(
    val id: String,
    val name: String,
    val status: String? = null,
    val isAgentic: Boolean? = null,
    val createdAt: Long = 0,
    val lastUpdatedAt: Long = 0,
    val bubbleCount: Int = 0,
)

@Serializable
data class IdeComposerMessage(
    val bubbleId: String,
    val role: String,
    val text: String = "",
    val createdAt: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: String? = null,
    val isStreaming: Boolean = false,
)

@Serializable
data class IdeComposerDetail(
    val id: String,
    val name: String,
    val status: String? = null,
    val isAgentic: Boolean? = null,
    val createdAt: Long = 0,
    val lastUpdatedAt: Long = 0,
    val bubbleCount: Int = 0,
    val messages: List<IdeComposerMessage> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class IdeListOk(
    val composers: List<IdeComposerSummary> = emptyList(),
    val available: Boolean,
    val bridgeReady: Boolean = false,
)

@Serializable
data class IdeGetOk(
    val composer: IdeComposerDetail? = null,
    val meta: IdeComposerMeta? = null,
)

@Serializable
data class IdeComposerMeta(
    val modelName: String? = null,
    val maxMode: Boolean? = null,
    val contextUsagePercent: Double? = null,
    val contextTokensUsed: Long? = null,
    val contextTokenLimit: Long? = null,
    val totalLinesAdded: Long? = null,
    val totalLinesRemoved: Long? = null,
    val filesChangedCount: Int? = null,
    val workspacePath: String? = null,
    val gitBranch: String? = null,
    val gitStatus: String? = null,
    val composerStatus: String? = null,
)

@Serializable
data class IdeStreamBody(
    val composerId: String,
    val runId: String,
    val event: IdeStreamEvent,
)

@Serializable
data class IdeStreamEvent(
    val type: String,
    val bubbleId: String? = null,
    val text: String? = null,
    val delta: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: String? = null,
    val status: String? = null,
    val isStreaming: Boolean? = null,
)

@Serializable
data class IdeRunResultBody(
    val composerId: String,
    val runId: String,
    val status: String,
    val summary: String? = null,
)

@Serializable
data class SysStatusOk(
    val daemonVersion: String,
    val cursorVersion: String? = null,
    val uptimeMs: Long,
    val agentCount: Int,
    val lanPort: Int? = null,
    val cursorApiKey: Boolean,
    val fcm: Boolean,
)

@Serializable
data class SysFcmBody(val token: String)

object MessageTypes {
    const val HELLO = "HELLO"
    const val HELLO_OK = "HELLO.OK"
    const val PAIR_REQUEST = "PAIR.REQUEST"
    const val PAIR_CLAIM = "PAIR.CLAIM"
    const val PAIR_OK = "PAIR.OK"
    const val ACK = "ACK"
    const val PING = "PING"
    const val PONG = "PONG"
    const val ERROR = "ERROR"

    const val AGENT_LIST = "AGENT.LIST"
    const val AGENT_LIST_RESULT = "AGENT.LIST.RESULT"
    const val AGENT_CREATE = "AGENT.CREATE"
    const val AGENT_CREATE_RESULT = "AGENT.CREATE.RESULT"
    const val AGENT_SEND = "AGENT.SEND"
    const val AGENT_STREAM = "AGENT.STREAM"
    const val AGENT_RUN_RESULT = "AGENT.RUN.RESULT"
    const val AGENT_CANCEL = "AGENT.CANCEL"
    const val AGENT_RESUME = "AGENT.RESUME"
    const val AGENT_GET = "AGENT.GET"
    const val AGENT_GET_RESULT = "AGENT.GET.RESULT"
    const val AGENT_MODELS = "AGENT.MODELS"
    const val AGENT_MODELS_RESULT = "AGENT.MODELS.RESULT"
    const val AGENT_SET_MODEL = "AGENT.SET_MODEL"
    const val AGENT_SET_MODEL_RESULT = "AGENT.SET_MODEL.RESULT"

    const val FS_LIST = "FS.LIST"
    const val FS_LIST_OK = "FS.LIST.OK"
    const val FS_READ = "FS.READ"
    const val FS_READ_OK = "FS.READ.OK"
    const val FS_WRITE = "FS.WRITE"
    const val FS_WRITE_OK = "FS.WRITE.OK"
    const val FS_OPEN_IN_IDE = "FS.OPEN_IN_IDE"
    const val FS_OPEN_IN_IDE_OK = "FS.OPEN_IN_IDE.OK"
    const val FS_WORKSPACES = "FS.WORKSPACES"
    const val FS_WORKSPACES_OK = "FS.WORKSPACES.OK"

    const val PTY_OPEN = "PTY.OPEN"
    const val PTY_OPENED = "PTY.OPENED"
    const val PTY_STDIN = "PTY.STDIN"
    const val PTY_STDOUT = "PTY.STDOUT"
    const val PTY_RESIZE = "PTY.RESIZE"
    const val PTY_CLOSE = "PTY.CLOSE"
    const val PTY_EXIT = "PTY.EXIT"
    const val PTY_LIST = "PTY.LIST"
    const val PTY_LIST_OK = "PTY.LIST.OK"
    const val PTY_ATTACH = "PTY.ATTACH"

    const val MCP_LIST = "MCP.LIST"
    const val MCP_LIST_OK = "MCP.LIST.OK"
    const val MCP_UPSERT = "MCP.UPSERT"
    const val MCP_UPSERT_OK = "MCP.UPSERT.OK"
    const val MCP_DELETE = "MCP.DELETE"
    const val MCP_DELETE_OK = "MCP.DELETE.OK"
    const val MCP_TOGGLE = "MCP.TOGGLE"
    const val MCP_TOGGLE_OK = "MCP.TOGGLE.OK"

    const val IDE_LIST = "IDE.LIST"
    const val IDE_LIST_OK = "IDE.LIST.OK"
    const val IDE_GET = "IDE.GET"
    const val IDE_GET_OK = "IDE.GET.OK"
    const val IDE_SEND = "IDE.SEND"
    const val IDE_CANCEL = "IDE.CANCEL"
    const val IDE_WATCH = "IDE.WATCH"
    const val IDE_UNWATCH = "IDE.UNWATCH"
    const val IDE_STREAM = "IDE.STREAM"
    const val IDE_RUN_RESULT = "IDE.RUN.RESULT"

    const val SYS_STATUS = "SYS.STATUS"
    const val SYS_STATUS_OK = "SYS.STATUS.OK"
    const val SYS_FCM = "SYS.FCM"
    const val SYS_FCM_OK = "SYS.FCM.OK"

    val CONTROL = setOf(HELLO, HELLO_OK, PAIR_REQUEST, PAIR_CLAIM, PAIR_OK, ACK, PING, PONG, ERROR)
}
