/**
 * Wire types shared with the relay and the Android app.
 * Keep this file dependency-free — it's pure types so we can mirror it
 * 1:1 in Kotlin (see android/.../Protocol.kt).
 */
export const PROTOCOL_VERSION = 1;

export interface Envelope<T = unknown> {
	v: number;
	id: string;
	seq: number;
	ack?: string;
	type: MessageType;
	ts: number;
	body?: T;
}

export type MessageType =
	// control
	| "HELLO"
	| "HELLO.OK"
	| "PAIR.REQUEST"
	| "PAIR.CLAIM"
	| "PAIR.OK"
	| "ACK"
	| "PING"
	| "PONG"
	| "ERROR"
	// agents
	| "AGENT.LIST"
	| "AGENT.LIST.RESULT"
	| "AGENT.CREATE"
	| "AGENT.CREATE.RESULT"
	| "AGENT.SEND"
	| "AGENT.STREAM"
	| "AGENT.RUN.RESULT"
	| "AGENT.CANCEL"
	| "AGENT.RESUME"
	| "AGENT.GET"
	| "AGENT.GET.RESULT"
	| "AGENT.MODELS"
	| "AGENT.MODELS.RESULT"
	| "AGENT.SET_MODEL"
	| "AGENT.SET_MODEL.RESULT"
	// files
	| "FS.LIST"
	| "FS.LIST.OK"
	| "FS.READ"
	| "FS.READ.OK"
	| "FS.WRITE"
	| "FS.WRITE.OK"
	| "FS.OPEN_IN_IDE"
	| "FS.OPEN_IN_IDE.OK"
	| "FS.WORKSPACES"
	| "FS.WORKSPACES.OK"
	// terminals
	| "PTY.OPEN"
	| "PTY.OPENED"
	| "PTY.STDIN"
	| "PTY.STDOUT"
	| "PTY.RESIZE"
	| "PTY.CLOSE"
	| "PTY.EXIT"
	| "PTY.LIST"
	| "PTY.LIST.OK"
	| "PTY.ATTACH"
	// mcp
	| "MCP.LIST"
	| "MCP.LIST.OK"
	| "MCP.UPSERT"
	| "MCP.UPSERT.OK"
	| "MCP.DELETE"
	| "MCP.DELETE.OK"
	| "MCP.TOGGLE"
	| "MCP.TOGGLE.OK"
	// IDE composer chats — mirror + submit into the user's actual Cursor IDE chats
	| "IDE.LIST"
	| "IDE.LIST.OK"
	| "IDE.GET"
	| "IDE.GET.OK"
	| "IDE.SEND"
	| "IDE.SEND.OK"
	| "IDE.CANCEL"
	| "IDE.WATCH"
	| "IDE.UNWATCH"
	| "IDE.STREAM"
	| "IDE.RUN.RESULT"
	// system
	| "SYS.STATUS"
	| "SYS.STATUS.OK"
	| "SYS.FCM"
	| "SYS.FCM.OK";

export interface AgentSummary {
	agentId: string;
	runtime: "local" | "cloud";
	model: string;
	cwd?: string;
	repos?: string[];
	createdAt: number;
	lastActivityAt: number;
	status: "idle" | "running" | "errored" | "finished";
	title?: string;
}

export interface AgentDetail extends AgentSummary {
	mcpServers?: string[];
}

export interface RunSummary {
	runId: string;
	status: "running" | "finished" | "error" | "cancelled";
	startedAt: number;
	endedAt?: number;
	summary?: string;
}

/** Normalized chat bubble for mobile history hydration. */
export interface AgentChatMessage {
	id: string;
	role: "user" | "assistant" | "tool" | "thinking" | "system";
	text: string;
	toolName?: string;
	toolInput?: string;
	toolOutput?: string;
	toolId?: string;
	toolStatus?: string;
	ts: number;
}

export interface ModelDescriptor {
	id: string;
	displayName?: string;
	supportsReasoning?: boolean;
	maxMode?: boolean;
}

export interface ErrorBody {
	code: string;
	message: string;
	retryable?: boolean;
}

export interface IdeComposerMessage {
	bubbleId: string;
	role: "user" | "assistant" | "tool";
	text: string;
	createdAt?: string | number;
	toolName?: string;
	toolInput?: string;
	toolOutput?: string;
	toolStatus?: string;
	isStreaming?: boolean;
}

export interface IdeStreamBody {
	composerId: string;
	runId: string;
	event: {
		type: "user" | "assistant_delta" | "assistant_done" | "tool" | "status";
		bubbleId?: string;
		text?: string;
		delta?: string;
		toolName?: string;
		toolInput?: string;
		toolOutput?: string;
		toolStatus?: string;
		status?: string;
		isStreaming?: boolean;
	};
}

export interface IdeRunResultBody {
	composerId: string;
	runId: string;
	status: "running" | "finished" | "error" | "cancelled";
	summary?: string;
}
