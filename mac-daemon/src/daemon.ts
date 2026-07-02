/**
 * Daemon bootstrap: wires services + transport + router.
 */
import { hostname } from "node:os";
import { loadConfig } from "./config.js";
import { deriveSharedKey } from "./crypto.js";
import { log } from "./logger.js";
import { attachRouter } from "./router.js";
import { AgentService } from "./services/agents.js";
import { FilesService } from "./services/files.js";
import { getComposerBridgeServer } from "./services/composer-bridge-server.js";
import { IdeService } from "./services/ide.js";
import { McpService } from "./services/mcp.js";
import { NotificationsService } from "./services/notifications.js";
import { TerminalService } from "./services/terminals.js";
import { Channel } from "./transport.js";

export async function runDaemon(): Promise<void> {
	const config = await loadConfig();

	const sharedKey =
		config.pairing?.peerPubKey && config.pairing?.ourPrivKey
			? deriveSharedKey(config.pairing.ourPrivKey, config.pairing.peerPubKey)
			: null;

	const channel = new Channel({
		config,
		sharedKey,
		deviceLabel: hostname(),
	});

	const notifications = new NotificationsService(config);
	const agents = new AgentService(config, {
		stream: (msg) =>
			channel.send("AGENT.STREAM", msg),
		runResult: (msg) => {
			channel.send("AGENT.RUN.RESULT", msg);
			void notifications.send({
				title: msg.status === "finished" ? "Agent finished" : `Agent ${msg.status}`,
				body: msg.summary?.slice(0, 200) ?? `Run ${msg.runId} ${msg.status}`,
				tag: msg.agentId,
				data: { agentId: msg.agentId, runId: msg.runId, status: msg.status },
			});
		},
		statusChanged: (msg) => channel.send("AGENT.STREAM", { ...msg, event: { type: "status" } }),
	});
	await agents.load();

	const files = new FilesService(config);
	const terminals = new TerminalService(config, {
		stdout: (msg) => channel.send("PTY.STDOUT", msg),
		exit: (msg) => channel.send("PTY.EXIT", msg),
	});
	const mcp = new McpService();
	const ide = new IdeService();
	const bridge = getComposerBridgeServer();
	await bridge.start();

	attachRouter({ config, channel, agents, files, terminals, mcp, notifications, ide });

	await channel.start();
	log.info(
		{
			lan: config.lan.enabled ? `${config.lan.bindHost}:${config.lan.port}` : "off",
			relay: config.pairing ? config.relayUrl : "not paired",
			hasKey: !!config.cursorApiKey,
		},
		"cursor-mobile-daemon ready",
	);

	for (const sig of ["SIGINT", "SIGTERM"] as const) {
		process.on(sig, async () => {
			log.info({ sig }, "shutting down");
			bridge.stop();
			ide.close();
			await channel.stop();
			process.exit(0);
		});
	}
}
