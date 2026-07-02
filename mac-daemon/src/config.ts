/**
 * Daemon config — persisted at ~/.cursor-mobile/config.json
 *
 * Holds:
 *   • Cursor API key (also reads CURSOR_API_KEY env if missing)
 *   • Relay URL
 *   • Long-lived pairing (pairId + our X25519 keypair + peer pubkey)
 *   • FCM bearer token + project for push notifications
 *   • Allowlist of directories the phone may browse (or null = $HOME)
 */
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { z } from "zod";

const ConfigSchema = z.object({
	cursorApiKey: z.string().optional(),
	relayUrl: z.string().url().default("https://cursor-mobile-relay.workers.dev"),
	lan: z
		.object({
			enabled: z.boolean().default(true),
			port: z.number().int().min(1024).max(65535).default(7681),
			bindHost: z.string().default("0.0.0.0"),
		})
		.default({}),
	pairing: z
		.object({
			pairId: z.string(),
			ourPubKey: z.string(),
			ourPrivKey: z.string(),
			peerPubKey: z.string().optional(),
			peerLabel: z.string().optional(),
			pairedAt: z.number().optional(),
		})
		.optional(),
	fcm: z
		.object({
			serverKey: z.string().optional(),
			projectId: z.string().optional(),
			deviceToken: z.string().optional(),
		})
		.default({}),
	fsAllowList: z.array(z.string()).default([]),
	defaultCwd: z.string().optional(),
});

export type DaemonConfig = z.infer<typeof ConfigSchema>;

const CONFIG_DIR = join(homedir(), ".cursor-mobile");
const CONFIG_PATH = join(CONFIG_DIR, "config.json");

export async function loadConfig(): Promise<DaemonConfig> {
	let raw = "{}";
	try {
		raw = await readFile(CONFIG_PATH, "utf8");
	} catch (err) {
		if ((err as NodeJS.ErrnoException).code !== "ENOENT") throw err;
	}
	const parsed = ConfigSchema.parse(JSON.parse(raw || "{}"));
	parsed.cursorApiKey ??= process.env.CURSOR_API_KEY;
	if (parsed.defaultCwd === undefined) parsed.defaultCwd = homedir();
	return parsed;
}

export async function saveConfig(cfg: DaemonConfig): Promise<void> {
	await mkdir(dirname(CONFIG_PATH), { recursive: true, mode: 0o700 });
	const toWrite = { ...cfg };
	delete (toWrite as { cursorApiKey?: string }).cursorApiKey;
	if (cfg.cursorApiKey && !process.env.CURSOR_API_KEY) {
		(toWrite as { cursorApiKey?: string }).cursorApiKey = cfg.cursorApiKey;
	}
	await writeFile(CONFIG_PATH, JSON.stringify(toWrite, null, 2), { mode: 0o600 });
}

export function configPath(): string {
	return CONFIG_PATH;
}
