/**
 * Router — dispatches inbound envelopes to the matching service method,
 * and pushes service events out as envelopes.
 *
 * All business logic lives in services; this file is the wire ⇄ method bridge.
 */
import type { DaemonConfig } from "./config.js";
import { log } from "./logger.js";
import type { Envelope, MessageType } from "./protocol.js";
import { AgentService } from "./services/agents.js";
import { FilesService } from "./services/files.js";
import { IdeService, isIdeGenerating } from "./services/ide.js";
import { McpService } from "./services/mcp.js";
import { NotificationsService } from "./services/notifications.js";
import { TerminalService } from "./services/terminals.js";
import type { Channel } from "./transport.js";

export interface RouterDeps {
	config: DaemonConfig;
	channel: Channel;
	agents: AgentService;
	files: FilesService;
	terminals: TerminalService;
	mcp: McpService;
	notifications: NotificationsService;
	ide: IdeService;
}

export function attachRouter(deps: RouterDeps) {
	const { channel } = deps;

	channel.on("message", async (env: Envelope) => {
		log.info({ type: env.type, id: env.id, ack: env.ack }, "rx");
		try {
			await dispatch(deps, env);
		} catch (err) {
			log.error({ err, type: env.type, id: env.id }, "handler threw");
			channel.send("ERROR", {
				code: "HANDLER_ERROR",
				message: err instanceof Error ? err.message : String(err),
			}, { ack: env.id });
		}
	});
}

async function dispatch(deps: RouterDeps, env: Envelope) {
	const t = env.type as MessageType;
	const body = (env.body ?? {}) as Record<string, unknown>;
	const { channel, agents, files, terminals, mcp, notifications, config } = deps;

	switch (t) {
		case "HELLO.OK":
		case "PAIR.OK":
		case "ACK":
		case "PING":
		case "PONG":
		case "ERROR":
			return;

		// agents
		case "AGENT.LIST": {
			const result = await agents.list(
				(body.runtime as "local" | "cloud" | "all") ?? "all",
				body.cwd as string | undefined,
			);
			channel.send("AGENT.LIST.RESULT", { agents: result }, { ack: env.id });
			return;
		}
		case "AGENT.CREATE": {
			const summary = await agents.create({
				cwd: body.cwd as string | undefined,
				model: (body.model as string) ?? "auto",
				runtime: (body.runtime as "local" | "cloud") ?? "local",
				repos: body.repos as string[] | undefined,
				initialPrompt: body.initialPrompt as string | undefined,
			});
			channel.send("AGENT.CREATE.RESULT", summary, { ack: env.id });
			return;
		}
		case "AGENT.SEND": {
			const result = await agents.send({
				agentId: body.agentId as string,
				prompt: body.prompt as string,
				runId: body.runId as string,
			});
			// Ack the send without emitting AGENT.RUN.RESULT — the phone treats
			// RUN.RESULT as terminal and would drop the streaming bubble early.
			channel.send("ACK", { ids: [env.id] }, { ack: env.id });
			void result;
			return;
		}
		case "AGENT.CANCEL": {
			await agents.cancel({
				agentId: body.agentId as string,
				runId: body.runId as string,
			});
			channel.send("AGENT.RUN.RESULT", {
				agentId: body.agentId,
				runId: body.runId,
				status: "cancelled",
			}, { ack: env.id });
			return;
		}
		case "AGENT.GET": {
			const detail = await agents.detail(body.agentId as string);
			channel.send("AGENT.GET.RESULT", detail, { ack: env.id });
			return;
		}
		case "AGENT.MODELS": {
			const models = await agents.listModels();
			channel.send("AGENT.MODELS.RESULT", { models }, { ack: env.id });
			return;
		}
		case "AGENT.SET_MODEL": {
			const agent = await agents.setModel(body.agentId as string, body.model as string);
			channel.send("AGENT.SET_MODEL.RESULT", agent, { ack: env.id });
			return;
		}
		case "AGENT.RESUME": {
			// resume implicit: agents service lazily resumes by id on next send.
			channel.send("AGENT.GET.RESULT", await agents.detail(body.agentId as string), { ack: env.id });
			return;
		}

		// files
		case "FS.LIST": {
			channel.send("FS.LIST.OK", await files.list(body.path as string), { ack: env.id });
			return;
		}
		case "FS.READ": {
			channel.send("FS.READ.OK", await files.read(body.path as string, body.maxBytes as number | undefined), { ack: env.id });
			return;
		}
		case "FS.WRITE": {
			channel.send("FS.WRITE.OK", await files.write(body.path as string, body.contentB64 as string), { ack: env.id });
			return;
		}
		case "FS.OPEN_IN_IDE": {
			channel.send("FS.OPEN_IN_IDE.OK", await files.openInIde(body.path as string), { ack: env.id });
			return;
		}
		case "FS.WORKSPACES": {
			channel.send("FS.WORKSPACES.OK", await files.workspaces(), { ack: env.id });
			return;
		}

		// terminals
		case "PTY.OPEN": {
			const sess = terminals.open({
				cwd: body.cwd as string | undefined,
				cols: body.cols as number | undefined,
				rows: body.rows as number | undefined,
				shell: body.shell as string | undefined,
			});
			channel.send("PTY.OPENED", sess, { ack: env.id });
			return;
		}
		case "PTY.STDIN":
			terminals.stdin(body.ptyId as string, body.dataB64 as string);
			return;
		case "PTY.RESIZE":
			terminals.resize(body.ptyId as string, body.cols as number, body.rows as number);
			return;
		case "PTY.CLOSE":
			terminals.close(body.ptyId as string);
			return;
		case "PTY.LIST":
			channel.send("PTY.LIST.OK", terminals.list(), { ack: env.id });
			return;
		case "PTY.ATTACH": {
			const chunks = terminals.attach(body.ptyId as string, body.sinceSeq as number);
			for (const c of chunks) {
				channel.send("PTY.STDOUT", { ptyId: body.ptyId, dataB64: c.b64, seq: c.seq });
			}
			return;
		}

		// mcp
		case "MCP.LIST": {
			channel.send("MCP.LIST.OK", await mcp.list(), { ack: env.id });
			return;
		}
		case "MCP.UPSERT": {
			const result = await mcp.upsert(
				body.scope as "user" | "project",
				body.projectPath as string | undefined,
				body.server as Parameters<McpService["upsert"]>[2],
			);
			channel.send("MCP.UPSERT.OK", { servers: result }, { ack: env.id });
			return;
		}
		case "MCP.DELETE": {
			const result = await mcp.remove(
				body.scope as "user" | "project",
				body.projectPath as string | undefined,
				body.name as string,
			);
			channel.send("MCP.DELETE.OK", { servers: result }, { ack: env.id });
			return;
		}
		case "MCP.TOGGLE": {
			const result = await mcp.toggle(
				body.scope as "user" | "project",
				body.projectPath as string | undefined,
				body.name as string,
				body.enabled as boolean,
			);
			channel.send("MCP.TOGGLE.OK", { servers: result }, { ack: env.id });
			return;
		}

		// IDE composer mirror
		case "IDE.LIST": {
			const limit = (body.limit as number | undefined) ?? 200;
			channel.send(
				"IDE.LIST.OK",
				{
					composers: deps.ide.list(limit),
					available: deps.ide.available(),
					bridgeReady: await deps.ide.bridgeReady(),
				},
				{ ack: env.id },
			);
			return;
		}
		case "IDE.GET": {
			const id = body.id as string;
			const tail = (body.tail as number | undefined) ?? 120;
			const detail = deps.ide.get(id, { tail });
			const meta = await deps.ide.getMeta(id);
			channel.send("IDE.GET.OK", { composer: detail, meta }, { ack: env.id });
			return;
		}
		case "IDE.WATCH": {
			const composerId = body.composerId as string;
			const runId = (body.runId as string) ?? env.id;
			deps.ide.watch(
				composerId,
				runId,
				(type, payload) => {
					channel.send(type, payload);
				},
				{ persist: true },
			);
			channel.send("ACK", { ids: [env.id] }, { ack: env.id });
			return;
		}
		case "IDE.UNWATCH": {
			deps.ide.unwatch(body.composerId as string);
			channel.send("ACK", { ids: [env.id] }, { ack: env.id });
			return;
		}
		case "IDE.SEND": {
			const composerId = body.composerId as string;
			const prompt = body.prompt as string;
			const runId = (body.runId as string) ?? env.id;
			// ACK immediately — insertIntoChat can take several seconds.
			channel.send("ACK", { ids: [env.id] }, { ack: env.id });
			try {
				await deps.ide.send(composerId, prompt);
			} catch (err) {
				channel.send("ERROR", {
					code: "IDE_SEND_FAILED",
					message: err instanceof Error ? err.message : String(err),
				});
				return;
			}
			deps.ide.watch(
				composerId,
				runId,
				(type, payload) => {
					channel.send(type, payload);
				},
				{ persist: true },
			);
			setTimeout(() => {
				const detail = deps.ide.get(composerId);
				if (detail && !isIdeGenerating(detail.status)) {
					channel.send("IDE.RUN.RESULT", { composerId, runId, status: "finished" });
				}
			}, 1200);
			return;
		}
		case "IDE.CANCEL": {
			const composerId = body.composerId as string;
			const runId = (body.runId as string) ?? env.id;
			await deps.ide.cancel(composerId);
			channel.send("ACK", { ids: [env.id] }, { ack: env.id });
			channel.send("IDE.RUN.RESULT", { composerId, runId, status: "cancelled" });
			return;
		}

		// system
		case "SYS.STATUS": {
			let agentCount = 0;
			try {
				agentCount = (await agents.list("all")).length;
			} catch {
				// agent list requires the Cursor API key. Status should still
				// answer even when no key is configured — the phone uses
				// `cursorApiKey:false` to surface a setup prompt.
			}
			channel.send("SYS.STATUS.OK", {
				daemonVersion: process.env.npm_package_version ?? "0.1.0",
				uptimeMs: Math.round(process.uptime() * 1000),
				agentCount,
				lanPort: config.lan.enabled ? config.lan.port : null,
				cursorApiKey: !!config.cursorApiKey,
				fcm: notifications.enabled(),
			}, { ack: env.id });
			return;
		}
		case "SYS.FCM": {
			notifications.registerToken(body.token as string);
			channel.send("SYS.FCM.OK", {}, { ack: env.id });
			return;
		}

		default:
			log.warn({ type: t }, "unhandled message type");
	}
}
