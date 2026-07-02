/**
 * Cursor IDE composer reader + submit bridge.
 */
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { DatabaseSync } from "node:sqlite";
import type { IdeRunResultBody, IdeStreamBody } from "../protocol.js";
import { log } from "../logger.js";
import { getComposerBridgeServer } from "./composer-bridge-server.js";

export interface IdeComposerSummary {
	id: string;
	name: string;
	status?: string;
	isAgentic?: boolean;
	createdAt: number;
	lastUpdatedAt: number;
	bubbleCount: number;
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

export interface IdeComposerDetail extends IdeComposerSummary {
	messages: IdeComposerMessage[];
	truncated?: boolean;
}

export interface IdeComposerMeta {
	modelName?: string;
	maxMode?: boolean;
	contextUsagePercent?: number;
	contextTokensUsed?: number;
	contextTokenLimit?: number;
	totalLinesAdded?: number;
	totalLinesRemoved?: number;
	filesChangedCount?: number;
	workspacePath?: string;
	gitBranch?: string;
	gitStatus?: string;
	composerStatus?: string;
}

const execAsync = promisify(exec);

const DB_PATH = join(
	homedir(),
	"Library",
	"Application Support",
	"Cursor",
	"User",
	"globalStorage",
	"state.vscdb",
);

const DEFAULT_TAIL = 120;
const POLL_IDLE_MS = 100;
const POLL_ACTIVE_MS = 50;

interface ParsedBubble {
	bubbleId: string;
	type: number;
	text: string;
	toolName?: string;
	toolInput?: string;
	toolOutput?: string;
	toolStatus?: string;
	createdAt?: string | number;
}

interface ComposerHead {
	id: string;
	name: string;
	status?: string;
	isAgentic?: boolean;
	createdAt: number;
	lastUpdatedAt: number;
	headers: Array<{ bubbleId: string; type?: number }>;
}

function parseBubbleJson(
	bubbleId: string,
	headerType: number | undefined,
	raw: string,
): ParsedBubble | null {
	try {
		const j = JSON.parse(raw) as {
			type?: number;
			text?: string;
			richText?: string;
			createdAt?: string | number;
			toolFormerData?: {
				name?: string;
				toolName?: string;
				status?: string;
				rawArgs?: string;
				params?: string;
				result?: string;
				additionalData?: { status?: string };
			};
		};
		const type = j.type ?? headerType ?? 0;
		const tf = j.toolFormerData;
		const toolName = tf?.name ?? tf?.toolName;
		const text = (j.text?.trim() || j.richText?.trim() || "").trim();

		if (toolName) {
			return {
				bubbleId,
				type: 2,
				text: text || "",
				toolName,
				toolInput: tf?.rawArgs ?? tf?.params,
				toolOutput: tf?.result,
				toolStatus: tf?.status ?? tf?.additionalData?.status ?? "completed",
				createdAt: j.createdAt,
			};
		}

		return {
			bubbleId,
			type,
			text,
			createdAt: j.createdAt,
		};
	} catch {
		return null;
	}
}

function toMessage(b: ParsedBubble, isStreaming = false): IdeComposerMessage {
	if (b.toolName) {
		return {
			bubbleId: b.bubbleId,
			role: "tool",
			text: b.text,
			toolName: b.toolName,
			toolInput: b.toolInput,
			toolOutput: b.toolOutput,
			toolStatus: b.toolStatus,
			createdAt: b.createdAt,
		};
	}
	if (b.type === 1) {
		return { bubbleId: b.bubbleId, role: "user", text: b.text, createdAt: b.createdAt };
	}
	return {
		bubbleId: b.bubbleId,
		role: "assistant",
		text: b.text,
		createdAt: b.createdAt,
		isStreaming,
	};
}

function isGenerating(status?: string): boolean {
	return status === "generating" || status === "applying";
}

export { isGenerating as isIdeGenerating };

export class IdeService {
	private db: DatabaseSync | null = null;
	private dbPath = DB_PATH;
	private lastOpenAttempt = 0;
	private bubbleStmt: ReturnType<DatabaseSync["prepare"]> | null = null;
	private watches = new Map<
		string,
		{
			runId: string;
			persist: boolean;
			timer: ReturnType<typeof setInterval>;
			onEvent: (t: string, b: unknown) => void;
		}
	>();

	private open(): DatabaseSync | null {
		if (this.db) return this.db;
		if (Date.now() - this.lastOpenAttempt < 5_000) return null;
		this.lastOpenAttempt = Date.now();
		if (!existsSync(this.dbPath)) {
			log.debug({ dbPath: this.dbPath }, "Cursor IDE state.vscdb not present");
			return null;
		}
		try {
			this.db = new DatabaseSync(this.dbPath, { readOnly: true });
			this.db.exec("PRAGMA query_only = 1;");
			this.bubbleStmt = this.db.prepare(`SELECT value FROM cursorDiskKV WHERE key = ?`);
			log.info("Cursor IDE DB opened (read-only)");
			return this.db;
		} catch (err) {
			log.warn({ err: (err as Error).message }, "could not open Cursor IDE DB");
			return null;
		}
	}

	available(): boolean {
		return this.open() !== null;
	}

	async bridgeReady(): Promise<boolean> {
		return getComposerBridgeServer().bridgeReady();
	}

	async send(composerId: string, text: string): Promise<void> {
		const detail = this.getHead(composerId);
		await getComposerBridgeServer().submit(composerId, text, detail?.name);
	}

	async cancel(composerId: string): Promise<void> {
		await getComposerBridgeServer().cancel(composerId);
	}

	private getHead(composerId: string): ComposerHead | null {
		const db = this.open();
		if (!db) return null;
		const head = db
			.prepare(`SELECT value FROM cursorDiskKV WHERE key = ?`)
			.get(`composerData:${composerId}`) as { value: string } | undefined;
		if (!head) return null;
		const headJson = JSON.parse(head.value) as {
			name?: string;
			status?: string;
			isAgentic?: boolean;
			createdAt?: number;
			lastUpdatedAt?: number;
			fullConversationHeadersOnly?: Array<{ bubbleId: string; type?: number }>;
		};
		const headers = headJson.fullConversationHeadersOnly ?? [];
		return {
			id: composerId,
			name: headJson.name ?? composerId.substring(0, 8),
			status: headJson.status,
			isAgentic: headJson.isAgentic,
			createdAt: headJson.createdAt ?? 0,
			lastUpdatedAt: headJson.lastUpdatedAt ?? headJson.createdAt ?? 0,
			headers,
		};
	}

	private loadBubble(
		composerId: string,
		header: { bubbleId: string; type?: number },
		generating: boolean,
		isLast: boolean,
	): IdeComposerMessage | null {
		const stmt = this.bubbleStmt;
		if (!stmt) return null;
		const row = stmt.get(`bubbleId:${composerId}:${header.bubbleId}`) as { value: string } | undefined;
		if (!row) return null;
		const parsed = parseBubbleJson(header.bubbleId, header.type, row.value);
		if (!parsed) return null;
		const streaming = generating && isLast && parsed.type !== 1 && !parsed.toolName;
		return toMessage(parsed, streaming);
	}

	get(composerId: string, opts?: { tail?: number }): IdeComposerDetail | null {
		const head = this.getHead(composerId);
		if (!head) return null;
		const tail = opts?.tail ?? DEFAULT_TAIL;
		const allHeaders = head.headers;
		const slice = allHeaders.length > tail ? allHeaders.slice(-tail) : allHeaders;
		const generating = isGenerating(head.status);
		const messages: IdeComposerMessage[] = [];

		for (let i = 0; i < slice.length; i++) {
			const msg = this.loadBubble(composerId, slice[i]!, generating, i === slice.length - 1);
			if (msg) messages.push(msg);
		}

		return {
			id: head.id,
			name: head.name,
			status: head.status,
			isAgentic: head.isAgentic,
			createdAt: head.createdAt,
			lastUpdatedAt: head.lastUpdatedAt,
			bubbleCount: allHeaders.length,
			messages,
			truncated: allHeaders.length > tail,
		};
	}

	async getMeta(composerId: string): Promise<IdeComposerMeta | null> {
		const db = this.open();
		if (!db) return null;
		const head = db
			.prepare(`SELECT value FROM cursorDiskKV WHERE key = ?`)
			.get(`composerData:${composerId}`) as { value: string } | undefined;
		if (!head) return null;
		const j = JSON.parse(head.value) as {
			status?: string;
			modelConfig?: { modelName?: string; maxMode?: boolean };
			contextUsagePercent?: number;
			contextTokensUsed?: number;
			contextTokenLimit?: number;
			totalLinesAdded?: number;
			totalLinesRemoved?: number;
			filesChangedCount?: number;
			workspaceIdentifier?: { uri?: { fsPath?: string; path?: string } };
			trackedGitRepos?: Array<{ repoPath?: string; branches?: Array<{ branchName?: string }> }>;
		};
		const workspacePath =
			j.workspaceIdentifier?.uri?.fsPath ??
			j.workspaceIdentifier?.uri?.path ??
			j.trackedGitRepos?.[0]?.repoPath;
		const gitBranch =
			j.trackedGitRepos?.[0]?.branches?.[0]?.branchName;
		let gitStatus: string | undefined;
		if (workspacePath && existsSync(join(workspacePath, ".git"))) {
			try {
				const { stdout } = await execAsync("git status --porcelain -b", {
					cwd: workspacePath,
					timeout: 5_000,
				});
				gitStatus = stdout.trim().slice(0, 2000) || "clean";
			} catch {
				gitStatus = undefined;
			}
		}
		return {
			modelName: j.modelConfig?.modelName,
			maxMode: j.modelConfig?.maxMode,
			contextUsagePercent: j.contextUsagePercent,
			contextTokensUsed: j.contextTokensUsed,
			contextTokenLimit: j.contextTokenLimit,
			totalLinesAdded: j.totalLinesAdded,
			totalLinesRemoved: j.totalLinesRemoved,
			filesChangedCount: j.filesChangedCount,
			workspacePath,
			gitBranch,
			gitStatus,
			composerStatus: j.status,
		};
	}

	watch(
		composerId: string,
		runId: string,
		onEvent: (type: "IDE.STREAM" | "IDE.RUN.RESULT", body: IdeStreamBody | IdeRunResultBody) => void,
		opts?: { persist?: boolean },
	): void {
		this.unwatch(composerId);
		const persist = opts?.persist === true;
		const knownBubbles = new Map<
			string,
			{ role: string; text: string; toolKey: string; toolStatus?: string }
		>();

		const seed = this.get(composerId, { tail: DEFAULT_TAIL });
		for (const m of seed?.messages ?? []) {
			knownBubbles.set(m.bubbleId, {
				role: m.role,
				text: m.text,
				toolKey: `${m.toolName ?? ""}|${m.toolInput ?? ""}|${m.toolOutput ?? ""}`,
				toolStatus: m.toolStatus,
			});
		}

		let headerCount = seed?.bubbleCount ?? 0;
		let lastStatus = seed?.status ?? "";
		let lastGenerating = isGenerating(lastStatus);
		let idleTicks = 0;
		let pollMs = POLL_IDLE_MS;

		const emitStatus = (status: string) => {
			onEvent("IDE.STREAM", {
				composerId,
				runId,
				event: { type: "status", status },
			});
		};

		const emitMessage = (m: IdeComposerMessage, generating: boolean) => {
			if (m.role === "user") {
				onEvent("IDE.STREAM", {
					composerId,
					runId,
					event: { type: "user", bubbleId: m.bubbleId, text: m.text },
				});
				return;
			}
			if (m.role === "tool") {
				onEvent("IDE.STREAM", {
					composerId,
					runId,
					event: {
						type: "tool",
						bubbleId: m.bubbleId,
						toolName: m.toolName,
						toolInput: m.toolInput,
						toolOutput: m.toolOutput,
						toolStatus: m.toolStatus,
						text: m.text,
					},
				});
				return;
			}
			if (m.text) {
				onEvent("IDE.STREAM", {
					composerId,
					runId,
					event: {
						type: "assistant_delta",
						bubbleId: m.bubbleId,
						text: m.text,
						delta: m.text,
						isStreaming: generating,
					},
				});
			}
		};

		const emitAssistantDelta = (
			m: IdeComposerMessage,
			prevText: string,
			generating: boolean,
		) => {
			const delta = m.text.slice(prevText.length);
			onEvent("IDE.STREAM", {
				composerId,
				runId,
				event: {
					type: "assistant_delta",
					bubbleId: m.bubbleId,
					text: m.text,
					delta,
					isStreaming: generating,
				},
			});
		};

		const tick = () => {
			const head = this.getHead(composerId);
			if (!head) return;

			const generating = isGenerating(head.status);
			if (generating !== lastGenerating) {
				emitStatus(generating ? "generating" : "idle");
				lastGenerating = generating;
				pollMs = generating ? POLL_ACTIVE_MS : POLL_IDLE_MS;
				const w = this.watches.get(composerId);
				if (w) {
					clearInterval(w.timer);
					w.timer = setInterval(tick, pollMs);
				}
			}

			const headers = head.headers;
			if (headers.length > headerCount) {
				const newHeaders = headers.slice(headerCount);
				for (let i = 0; i < newHeaders.length; i++) {
					const h = newHeaders[i]!;
					const isLast = i === newHeaders.length - 1 && headerCount + i === headers.length - 1;
					const msg = this.loadBubble(composerId, h, generating, isLast);
					if (!msg) continue;
					knownBubbles.set(msg.bubbleId, {
						role: msg.role,
						text: msg.text,
						toolKey: `${msg.toolName ?? ""}|${msg.toolInput ?? ""}|${msg.toolOutput ?? ""}`,
						toolStatus: msg.toolStatus,
					});
					emitMessage(msg, generating);
				}
				headerCount = headers.length;
			} else if (headers.length > 0) {
				const lastH = headers.at(-1)!;
				const msg = this.loadBubble(composerId, lastH, generating, true);
				if (msg) {
					const prev = knownBubbles.get(msg.bubbleId);
					const toolKey = `${msg.toolName ?? ""}|${msg.toolInput ?? ""}|${msg.toolOutput ?? ""}`;

					if (!prev) {
						knownBubbles.set(msg.bubbleId, {
							role: msg.role,
							text: msg.text,
							toolKey,
							toolStatus: msg.toolStatus,
						});
						emitMessage(msg, generating);
					} else if (msg.role === "assistant" && msg.text.length > prev.text.length) {
						emitAssistantDelta(msg, prev.text, generating);
						prev.text = msg.text;
					} else if (
						msg.role === "tool" &&
						(toolKey !== prev.toolKey || msg.toolStatus !== prev.toolStatus)
					) {
						prev.toolKey = toolKey;
						prev.toolStatus = msg.toolStatus;
						onEvent("IDE.STREAM", {
							composerId,
							runId,
							event: {
								type: "tool",
								bubbleId: msg.bubbleId,
								toolName: msg.toolName,
								toolInput: msg.toolInput,
								toolOutput: msg.toolOutput,
								toolStatus: msg.toolStatus,
								text: msg.text,
							},
						});
					}
				}
			}

			const status = head.status ?? "";
			if (generating) {
				idleTicks = 0;
				lastStatus = status;
				return;
			}
			if (isGenerating(lastStatus)) {
				emitStatus("idle");
				const lastH = headers.at(-1);
				if (lastH) {
					const msg = this.loadBubble(composerId, lastH, false, true);
					if (msg?.role === "assistant" && msg.text) {
						onEvent("IDE.STREAM", {
							composerId,
							runId,
							event: {
								type: "assistant_delta",
								bubbleId: msg.bubbleId,
								text: msg.text,
								delta: "",
								isStreaming: false,
							},
						});
					}
				}
				onEvent("IDE.RUN.RESULT", { composerId, runId, status: "finished" });
				if (!persist) {
					this.unwatch(composerId);
				}
				lastStatus = status;
				return;
			}
			idleTicks++;
			if (!persist && idleTicks > 30) {
				onEvent("IDE.RUN.RESULT", { composerId, runId, status: "finished" });
				this.unwatch(composerId);
			}
			lastStatus = status;
		};

		const timer = setInterval(tick, pollMs);
		tick();
		this.watches.set(composerId, {
			runId,
			persist,
			timer,
			onEvent: onEvent as (t: string, b: unknown) => void,
		});
	}

	unwatch(composerId: string): void {
		const w = this.watches.get(composerId);
		if (w) {
			clearInterval(w.timer);
			this.watches.delete(composerId);
		}
	}

	list(limit = 200): IdeComposerSummary[] {
		const db = this.open();
		if (!db) return [];
		const stmt = db.prepare(`SELECT key, value FROM cursorDiskKV WHERE key LIKE 'composerData:%'`);
		const rows = stmt.all() as Array<{ key: string; value: string }>;
		const out: IdeComposerSummary[] = [];
		for (const r of rows) {
			try {
				const j = JSON.parse(r.value) as {
					name?: string;
					status?: string;
					isAgentic?: boolean;
					createdAt?: number;
					lastUpdatedAt?: number;
					fullConversationHeadersOnly?: unknown[];
				};
				if (!j.name) continue;
				const id = r.key.substring("composerData:".length);
				out.push({
					id,
					name: j.name,
					status: j.status,
					isAgentic: j.isAgentic,
					createdAt: j.createdAt ?? 0,
					lastUpdatedAt: j.lastUpdatedAt ?? j.createdAt ?? 0,
					bubbleCount: Array.isArray(j.fullConversationHeadersOnly)
						? j.fullConversationHeadersOnly.length
						: 0,
				});
			} catch {
				// skip
			}
		}
		out.sort((a, b) => b.lastUpdatedAt - a.lastUpdatedAt);
		return out.slice(0, limit);
	}

	close(): void {
		for (const id of [...this.watches.keys()]) this.unwatch(id);
		this.bubbleStmt = null;
		if (this.db) {
			try {
				this.db.close();
			} catch {
				// ignore
			}
			this.db = null;
		}
	}
}
