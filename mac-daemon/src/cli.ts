#!/usr/bin/env node
/**
 * cursor-mobile-daemon CLI.
 *
 *   run                 Start the daemon (intended for launchd).
 *   pair                Mint a pairing code + QR; wait for phone to claim.
 *   config show         Print current config (key redacted).
 *   config set-key      Store CURSOR_API_KEY in config file.
 *   config set-relay    Change relay URL.
 *   unpair              Forget current pairing.
 */
import { Buffer } from "node:buffer";
import { hostname } from "node:os";
import qrcode from "qrcode-terminal";
import { ulid } from "ulid";
import { configPath, loadConfig, saveConfig } from "./config.js";
import { generateKeyPair, pubKeyFingerprint } from "./crypto.js";
import { runDaemon } from "./daemon.js";
import { log } from "./logger.js";

const [, , cmd, ...args] = process.argv;

async function main() {
	switch (cmd) {
		case "run":
			await runDaemon();
			return;
		case "pair":
			await pair();
			return;
		case "unpair": {
			const cfg = await loadConfig();
			delete cfg.pairing;
			await saveConfig(cfg);
			console.log("unpaired.");
			return;
		}
		case "test": {
			// Health check: hits the Cursor SDK end-to-end so you can verify
			// the API key + connectivity without bringing the phone into it.
			await selfTest();
			return;
		}
		case "config": {
			const sub = args[0];
			if (sub === "show") return showConfig();
			if (sub === "set-key") return setKey(args[1]);
			if (sub === "set-relay") return setRelay(args[1]);
			console.log("usage: config <show | set-key <KEY> | set-relay <URL>>");
			process.exitCode = 2;
			return;
		}
		case "--help":
		case "-h":
		case undefined:
			console.log(
				`cursor-mobile-daemon\n\n` +
					`  run                 start the daemon\n` +
					`  pair                generate a pairing code/QR for your phone\n` +
					`  unpair              forget current pairing\n` +
					`  test                end-to-end Cursor SDK health check\n` +
					`  config show         print current config\n` +
					`  config set-key K    store CURSOR_API_KEY\n` +
					`  config set-relay U  change relay URL\n`,
			);
			return;
		default:
			console.error(`unknown command: ${cmd}`);
			process.exitCode = 2;
	}
}

async function showConfig() {
	const cfg = await loadConfig();
	const safe = {
		...cfg,
		cursorApiKey: cfg.cursorApiKey ? "***redacted***" : null,
		fcm: { ...cfg.fcm, serverKey: cfg.fcm.serverKey ? "***redacted***" : null },
		pairing: cfg.pairing
			? { ...cfg.pairing, ourPrivKey: "***redacted***" }
			: null,
	};
	console.log("# " + configPath());
	console.log(JSON.stringify(safe, null, 2));
}

async function setKey(key?: string) {
	if (!key) {
		console.error("usage: config set-key <CURSOR_API_KEY>");
		process.exitCode = 2;
		return;
	}
	const cfg = await loadConfig();
	cfg.cursorApiKey = key.trim();
	await saveConfig(cfg);
	console.log("saved.");
}

async function setRelay(url?: string) {
	if (!url) {
		console.error("usage: config set-relay <RELAY_WORKER_URL>");
		process.exitCode = 2;
		return;
	}
	const cfg = await loadConfig();
	cfg.relayUrl = url.trim().replace(/\/$/, "");
	await saveConfig(cfg);
	console.log("saved.");
}

async function selfTest() {
	const { Agent, Cursor } = await import("@cursor/sdk");
	const cfg = await loadConfig();
	const apiKey = cfg.cursorApiKey;
	if (!apiKey) {
		console.error("no API key set. run `config set-key cursor_…` first.");
		process.exit(1);
	}
	process.stdout.write("→ Cursor.models.list… ");
	const models = await Cursor.models.list({ apiKey });
	console.log(`ok (${models.length} models, default=${models[0]?.id ?? "?"})`);

	process.stdout.write("→ Agent.create local… ");
	await using agent = await Agent.create({
		apiKey,
		model: { id: "auto" },
		local: { cwd: process.cwd() },
	});
	console.log(`ok (${agent.agentId})`);

	process.stdout.write("→ agent.send 'ping' → stream… ");
	const run = await agent.send(
		"Reply with just the literal text 'pong' and nothing else.",
	);
	let text = "";
	for await (const ev of run.stream()) {
		if (ev.type === "assistant") {
			for (const block of (ev.message.content ?? [])) {
				if (block.type === "text") text += block.text ?? "";
			}
		}
	}
	const result = await run.wait();
	console.log(`ok (status=${result.status}, text=${JSON.stringify(text.trim())})`);

	console.log("\n✓ Daemon is fully functional. Open Cursor Mobile and pair.");
}

async function restartDaemonIfInstalled() {
	const uid = process.getuid?.();
	if (uid == null) {
		console.log("  Restart the daemon: cursor-mobile-daemon run");
		return;
	}
	try {
		const { execSync } = await import("node:child_process");
		execSync(`launchctl kickstart -k gui/${uid}/com.cursormobile.daemon`, {
			stdio: "ignore",
		});
		console.log("  Daemon restarted via launchd.");
	} catch {
		console.log(
			"  Restart the daemon: launchctl kickstart -k gui/$(id -u)/com.cursormobile.daemon",
		);
	}
}

async function pair() {
	const cfg = await loadConfig();
	if (!cfg.cursorApiKey) {
		console.warn(
			"warn: CURSOR_API_KEY not set. Pairing will work, but the agents tab will be disabled until you run `config set-key`.",
		);
	}

	const res = await fetch(`${cfg.relayUrl.replace(/\/$/, "")}/pair/start`, {
		method: "POST",
	});
	if (!res.ok) {
		console.error(`pair/start failed: ${res.status} ${await res.text()}`);
		process.exit(1);
	}
	const { pairId, code, ttlSeconds } = (await res.json()) as {
		pairId: string;
		code: string;
		ttlSeconds: number;
	};

	const kp = cfg.pairing?.ourPrivKey
		? { pubKey: cfg.pairing.ourPubKey, privKey: cfg.pairing.ourPrivKey }
		: generateKeyPair();

	const fingerprint = pubKeyFingerprint(kp.pubKey);
	const wsUrl = cfg.relayUrl.replace(/^http/, "ws") + `/pair/${pairId}/ws?role=mac`;

	// Pair payload encoded into the QR for the phone.
	const qrPayload = JSON.stringify({
		v: 1,
		relay: cfg.relayUrl,
		pairId,
		code,
		fp: fingerprint,
		label: hostname(),
	});
	const qrUri = `cm1://pair#${Buffer.from(qrPayload).toString("base64url")}`;

	console.log("\n— Cursor Mobile pairing —");
	console.log(`PairId:      ${pairId}`);
	console.log(`Code:        ${code}`);
	console.log(`Fingerprint: ${fingerprint}`);
	console.log(`TTL:         ${ttlSeconds}s`);
	console.log(`Deep link:   ${qrUri}`);
	console.log("\nScan this QR with the Cursor Mobile app:\n");
	qrcode.generate(qrUri, { small: true });

	// Open a WS for the daemon side so the phone has someone to handshake with.
	const { WebSocket } = await import("ws");
	const ws = new WebSocket(wsUrl);
	const done = new Promise<void>((resolve, reject) => {
		const t = setTimeout(() => reject(new Error("pair timeout")), ttlSeconds * 1000);
		ws.on("open", () => {
			ws.send(
				JSON.stringify({
					v: 1,
					id: ulid(),
					seq: 0,
					type: "HELLO",
					ts: Date.now(),
					body: {
						role: "mac",
						pairId,
						pubKey: kp.pubKey,
						deviceLabel: hostname(),
					},
				}),
			);
			ws.send(
				JSON.stringify({
					v: 1,
					id: ulid(),
					seq: 0,
					type: "PAIR.CLAIM",
					ts: Date.now(),
					body: { code, pubKey: kp.pubKey, deviceLabel: hostname() },
				}),
			);
			console.log("\nWaiting for phone…");
		});
		ws.on("message", async (data) => {
			const env = JSON.parse(data.toString()) as {
				type: string;
				body?: { peerPubKey?: string; peerLabel?: string; paired?: boolean };
			};
			if (env.type === "PAIR.OK" && env.body?.peerPubKey) {
				cfg.pairing = {
					pairId,
					ourPubKey: kp.pubKey,
					ourPrivKey: kp.privKey,
					peerPubKey: env.body.peerPubKey,
					peerLabel: env.body.peerLabel,
					pairedAt: Date.now(),
				};
				await saveConfig(cfg);
				console.log(`\n✓ Paired with ${env.body.peerLabel ?? "phone"}.`);
				await restartDaemonIfInstalled();
				clearTimeout(t);
				ws.close();
				resolve();
			} else if (env.type === "ERROR") {
				console.error("relay error", env.body);
			}
		});
		ws.on("error", reject);
	});
	await done;
}

main().catch((err) => {
	log.error({ err: err.message ?? err }, "cli failed");
	process.exit(1);
});
