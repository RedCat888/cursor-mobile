/**
 * Agent service — wraps @cursor/sdk so the phone can drive Cursor agents
 * end-to-end: list, create, send, stream, cancel, resume, list models.
 *
 *   • Live `SDKAgent` handles live in `this.handles`; we dispose on cancel/exit
 *     and we lazily resume by id when the phone reattaches.
 *   • Each `run.stream()` is forwarded as `AGENT.STREAM` envelopes with `runId`
 *     so the phone can render multiple concurrent runs.
 *   • A `CursorAgentError` thrown by an SDK call means the run never started:
 *     we surface it as an `ERROR` frame. Errors during a run become a non-zero
 *     `result.status` in the final `AGENT.RUN.RESULT`.
 *   • We persist a tiny journal at `~/.cursor-mobile/agents.json` so cached
 *     agents survive daemon restarts.
 */
import { Buffer } from "node:buffer";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { webcrypto } from "node:crypto";
import {
	Agent,
	Cursor,
	CursorAgentError,
	type AgentMessage,
	type Run,
	type SDKAgent,
	type SDKAgentInfo,
} from "@cursor/sdk";
import type { DaemonConfig } from "../config.js";
import { log } from "../logger.js";
import type {
	AgentDetail,
	AgentChatMessage,
	AgentSummary,
	ModelDescriptor,
	RunSummary,
} from "../protocol.js";

interface JournalEntry extends AgentSummary {
	runs: RunSummary[];
}

const JOURNAL_PATH = join(homedir(), ".cursor-mobile", "agents.json");

export interface AgentEmit {
	stream: (msg: { agentId: string; runId: string; event: unknown }) => void;
	runResult: (msg: { agentId: string; runId: string; status: string; summary?: string }) => void;
	statusChanged: (msg: { agentId: string; status: AgentSummary["status"] }) => void;
}

export class AgentService {
	private handles = new Map<string, SDKAgent>();
	private runs = new Map<string, Run>();
	private journal = new Map<string, JournalEntry>();
	private emit: AgentEmit;

	constructor(private cfg: DaemonConfig, emit: AgentEmit) {
		this.emit = emit;
	}

	async load(): Promise<void> {
		try {
			const raw = await readFile(JOURNAL_PATH, "utf8");
			const entries = JSON.parse(raw) as JournalEntry[];
			for (const e of entries) this.journal.set(e.agentId, e);
		} catch (err) {
			if ((err as NodeJS.ErrnoException).code !== "ENOENT") {
				log.warn({ err }, "could not load agent journal");
			}
		}
	}

	private async persist(): Promise<void> {
		await mkdir(join(homedir(), ".cursor-mobile"), { recursive: true, mode: 0o700 });
		const arr = [...this.journal.values()];
		await writeFile(JOURNAL_PATH, JSON.stringify(arr, null, 2), { mode: 0o600 });
	}

	private requireKey(): string {
		const key = this.cfg.cursorApiKey;
		if (!key) {
			throw new Error("CURSOR_API_KEY missing. Run `cursor-mobile-daemon config set-key …`.");
		}
		return key;
	}

	async listModels(): Promise<ModelDescriptor[]> {
		const apiKey = this.requireKey();
		const models = await Cursor.models.list({ apiKey });
		return models.map((m) => ({
			id: m.id,
			displayName: m.displayName ?? m.id,
			supportsReasoning: Boolean(m.parameters?.find((p) => p.id === "reasoningEffort")),
			maxMode: Boolean(m.variants?.find((v) => v.displayName.toLowerCase().includes("max"))),
		}));
	}

	async list(runtime: "local" | "cloud" | "all", cwd?: string): Promise<AgentSummary[]> {
		const apiKey = this.requireKey();
		const out: AgentSummary[] = [];

		if (runtime === "local" || runtime === "all") {
			try {
				// Omit `cwd` so the SDK returns every locally-tracked agent
				// regardless of the project it was scoped to. The phone can
				// filter by workspace client-side; including a cwd here only
				// hides agents the user created elsewhere.
				const result = await Agent.list(
					cwd ? { runtime: "local", cwd } : { runtime: "local" },
				);
				for (const a of result.items) out.push(this.fromSdk(a));
			} catch (err) {
				log.warn({ err }, "local agents.list failed");
			}
		}
		if (runtime === "cloud" || runtime === "all") {
			try {
				const result = await Agent.list({ runtime: "cloud", apiKey });
				for (const a of result.items) out.push(this.fromSdk(a));
			} catch (err) {
				log.warn({ err }, "cloud agents.list failed");
			}
		}

		// Merge with journal so cached agents still appear.
		for (const j of this.journal.values()) {
			if (!out.find((o) => o.agentId === j.agentId)) out.push(j);
		}
		out.sort((a, b) => b.lastActivityAt - a.lastActivityAt);
		return out;
	}

	private fromSdk(info: SDKAgentInfo): AgentSummary {
		const runtime: "local" | "cloud" = info.agentId.startsWith("bc-") ? "cloud" : "local";
		const status: AgentSummary["status"] = (() => {
			switch (info.status) {
				case "running":
				case "finished":
					return info.status;
				case "error":
					return "errored";
				default:
					return "idle";
			}
		})();
		const cwd =
			info.runtime === "local" ? info.cwd : undefined;
		const repos =
			info.runtime === "cloud" ? info.repos : undefined;
		return {
			agentId: info.agentId,
			runtime,
			model: "auto", // SDKAgentInfo doesn't carry the model; resolved on detail
			cwd,
			repos,
			createdAt: info.createdAt ?? info.lastModified ?? Date.now(),
			lastActivityAt: info.lastModified ?? Date.now(),
			status,
			title: info.name && info.name !== "New Agent" ? info.name : (info.summary?.slice(0, 80) || info.name),
		};
	}

	async create(args: {
		cwd?: string;
		model: string;
		runtime: "local" | "cloud";
		repos?: string[];
		initialPrompt?: string;
	}): Promise<AgentSummary> {
		const apiKey = this.requireKey();
		const base = {
			apiKey,
			model: { id: args.model },
		};
		const handle = await (args.runtime === "cloud"
			? Agent.create({
					...base,
					cloud: {
						repos: (args.repos ?? []).map((url) => ({ url })),
					},
				})
			: Agent.create({
					...base,
					local: { cwd: args.cwd ?? this.cfg.defaultCwd ?? process.cwd() },
				}));
		this.handles.set(handle.agentId, handle);

		const summary: AgentSummary = {
			agentId: handle.agentId,
			runtime: args.runtime,
			model: args.model,
			cwd: args.cwd ?? this.cfg.defaultCwd,
			repos: args.repos,
			createdAt: Date.now(),
			lastActivityAt: Date.now(),
			status: "idle",
		};
		this.journal.set(handle.agentId, { ...summary, runs: [] });
		await this.persist();

		if (args.initialPrompt) {
			void this.send({ agentId: handle.agentId, prompt: args.initialPrompt, runId: cryptoRunId() });
		}
		return summary;
	}

	private async getHandle(agentId: string): Promise<SDKAgent> {
		const cached = this.handles.get(agentId);
		if (cached) return cached;
		const apiKey = this.requireKey();
		// Resume requires the model again for local agents (the SDK does not
		// remember it across processes). Use the journal entry's model when
		// available, falling back to "auto".
		const journal = this.journal.get(agentId);
		const handle = await Agent.resume(agentId, {
			apiKey,
			model: { id: journal?.model ?? "auto" },
		});
		this.handles.set(agentId, handle);
		return handle;
	}

	async send(args: { agentId: string; prompt: string; runId: string }): Promise<RunSummary> {
		const handle = await this.getHandle(args.agentId);
		// Always pass `model` on send: the SDK requires it for local agents
		// on every call and tolerates it for cloud agents.
		const journal = this.journal.get(args.agentId);
		const model = journal?.model ?? "auto";
		const run = await handle.send(args.prompt, { model: { id: model } });
		this.runs.set(args.runId, run);
		this.markStatus(args.agentId, "running");

		const summary: RunSummary = {
			runId: args.runId,
			status: "running",
			startedAt: Date.now(),
		};

		if (journal) {
			journal.runs.push(summary);
			journal.lastActivityAt = Date.now();
			await this.persist();
		}

		void this.streamRun(args.agentId, args.runId, run);
		return summary;
	}

	private async streamRun(agentId: string, runId: string, run: Run) {
		try {
			for await (const event of run.stream()) {
				this.emit.stream({ agentId, runId, event: event as unknown });
			}
			const result = await run.wait();
			this.markStatus(agentId, result.status === "finished" ? "finished" : "errored");
			this.emit.runResult({
				agentId,
				runId,
				status: String(result.status),
				summary: typeof result.result === "string" ? result.result : undefined,
			});
		} catch (err) {
			this.markStatus(agentId, "errored");
			const isStartFail = err instanceof CursorAgentError;
			this.emit.runResult({
				agentId,
				runId,
				status: isStartFail ? "start_error" : "error",
				summary: err instanceof Error ? err.message : String(err),
			});
		} finally {
			this.runs.delete(runId);
		}
	}

	async cancel(args: { agentId: string; runId: string }): Promise<void> {
		const run = this.runs.get(args.runId);
		if (run && run.supports("cancel")) await run.cancel();
		this.markStatus(args.agentId, "idle");
	}

	async detail(agentId: string): Promise<{ agent: AgentDetail; recentRuns: RunSummary[]; messages: AgentChatMessage[] }> {
		const apiKey = this.requireKey();
		const journal = this.journal.get(agentId);
		const recent = journal ? journal.runs.slice(-50) : [];
		let agent: AgentDetail;
		try {
			const info = await Agent.get(agentId, { apiKey });
			const summary = this.fromSdk(info);
			if (journal) summary.model = journal.model;
			if (journal && !summary.title) summary.title = journal.title;
			agent = { ...summary };
		} catch (err) {
			if (journal) agent = { ...journal };
			else throw err;
		}
		const messages = await this.loadMessages(agentId, agent.cwd ?? journal?.cwd);
		return { agent, recentRuns: recent, messages };
	}

	async setModel(agentId: string, model: string): Promise<AgentSummary> {
		this.requireKey();
		let journal = this.journal.get(agentId);
		if (!journal) {
			const detail = await this.detail(agentId);
			journal = { ...detail.agent, runs: detail.recentRuns };
			this.journal.set(agentId, journal);
		}
		journal.model = model;
		journal.lastActivityAt = Date.now();
		await this.persist();
		return journal;
	}

	private async loadMessages(agentId: string, cwd?: string): Promise<AgentChatMessage[]> {
		const isCloud = agentId.startsWith("bc-");
		if (isCloud) {
			try {
				const apiKey = this.requireKey();
				const runs = await Agent.listRuns(agentId, { runtime: "cloud", apiKey, limit: 30 });
				const out: AgentChatMessage[] = [];
				for (const run of runs.items) {
					if (!run.supports("conversation")) continue;
					const turns = await run.conversation();
					out.push(...this.normalizeConversationTurns(agentId, run.id, turns));
				}
				return out;
			} catch (err) {
				log.warn({ err, agentId }, "cloud conversation load failed");
				return [];
			}
		}

		try {
			const raw = await Agent.messages.list(agentId, {
				runtime: "local",
				cwd: cwd ?? this.cfg.defaultCwd ?? process.cwd(),
				limit: 500,
			});
			return this.normalizeAgentMessages(raw);
		} catch (err) {
			log.warn({ err, agentId }, "local messages.list failed");
			return [];
		}
	}

	private normalizeAgentMessages(raw: AgentMessage[]): AgentChatMessage[] {
		const out: AgentChatMessage[] = [];
		for (const m of raw) {
			const turn = (m.message as { agentConversationTurn?: ConversationTurnPayload })?.agentConversationTurn;
			if (!turn) continue;
			const userText = turn.userMessage?.text?.trim();
			if (userText) {
				out.push({
					id: `${m.uuid}:user`,
					role: "user",
					text: userText,
					ts: Date.now(),
				});
			}
			for (const [i, step] of (turn.steps ?? []).entries()) {
				out.push(...this.normalizeStep(`${m.uuid}:${i}`, step));
			}
		}
		return out;
	}

	private normalizeConversationTurns(
		agentId: string,
		runId: string,
		turns: unknown[],
	): AgentChatMessage[] {
		const out: AgentChatMessage[] = [];
		for (const [i, raw] of turns.entries()) {
			const turn = raw as Record<string, unknown>;
			const type = String(turn.type ?? "");
			if (type === "userMessage") {
				const msg = turn.message as { text?: string };
				out.push({
					id: `${runId}:${i}:user`,
					role: "user",
					text: msg?.text ?? "",
					ts: Date.now(),
				});
			} else if (type === "assistantMessage") {
				const msg = turn.message as { text?: string };
				out.push({
					id: `${runId}:${i}:assistant`,
					role: "assistant",
					text: msg?.text ?? "",
					ts: Date.now(),
				});
			} else if (type === "toolCall") {
				out.push(this.toolCallToChat(`${runId}:${i}:tool`, turn.message as ToolCallPayload));
			} else if (type === "thinking") {
				const msg = turn.message as { text?: string };
				out.push({
					id: `${runId}:${i}:thinking`,
					role: "thinking",
					text: msg?.text ?? "",
					ts: Date.now(),
				});
			}
		}
		void agentId;
		return out;
	}

	private normalizeStep(idPrefix: string, step: ConversationStepPayload): AgentChatMessage[] {
		const out: AgentChatMessage[] = [];
		if (step.assistantMessage?.text) {
			out.push({
				id: `${idPrefix}:assistant`,
				role: "assistant",
				text: step.assistantMessage.text,
				ts: Date.now(),
			});
		}
		if (step.thinkingMessage?.text) {
			out.push({
				id: `${idPrefix}:thinking`,
				role: "thinking",
				text: step.thinkingMessage.text,
				ts: Date.now(),
			});
		}
		if (step.type === "toolCall" && step.message) {
			out.push(this.toolCallToChat(`${idPrefix}:tool`, step.message as ToolCallPayload));
		}
		return out;
	}

	private toolCallToChat(id: string, msg: ToolCallPayload): AgentChatMessage {
		const toolType = msg.type ?? "tool";
		const args = msg.args ?? {};
		const result = msg.result;
		let toolOutput: string | undefined;
		let toolStatus = "running";
		if (result) {
			toolStatus = result.status === "success" ? "completed" : "error";
			toolOutput = JSON.stringify(result.value ?? result.error ?? result);
		}
		return {
			id,
			role: "tool",
			text: toolType,
			toolName: toolType,
			toolInput: JSON.stringify(args),
			toolOutput,
			toolId: id,
			toolStatus,
			ts: Date.now(),
		};
	}

	async dispose(agentId: string): Promise<void> {
		const h = this.handles.get(agentId);
		this.handles.delete(agentId);
		if (h) {
			try {
				await h[Symbol.asyncDispose]();
			} catch (err) {
				log.warn({ err }, "dispose agent failed");
			}
		}
	}

	private markStatus(agentId: string, status: AgentSummary["status"]) {
		const j = this.journal.get(agentId);
		if (j) {
			j.status = status;
			j.lastActivityAt = Date.now();
			void this.persist();
		}
		this.emit.statusChanged({ agentId, status });
	}
}

function cryptoRunId(): string {
	const bytes = new Uint8Array(8);
	webcrypto.getRandomValues(bytes);
	return Buffer.from(bytes).toString("hex");
}

interface ConversationTurnPayload {
	userMessage?: { text?: string };
	steps?: ConversationStepPayload[];
}

interface ConversationStepPayload {
	type?: string;
	assistantMessage?: { text?: string };
	thinkingMessage?: { text?: string };
	message?: ToolCallPayload;
}

interface ToolCallPayload {
	type?: string;
	args?: Record<string, unknown>;
	result?: { status?: string; value?: unknown; error?: unknown };
}
