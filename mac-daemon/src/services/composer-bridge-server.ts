/**
 * Composer bridge orchestration — extension owns the socket and calls
 * ComposerService.insertIntoChat. The daemon is always a client.
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { log } from "../logger.js";
import { detectGlassMode } from "./glass-submit.js";
import { ensureBridgeHost } from "./bridge-host.js";
import {
	extensionBridgeAvailable,
	extensionComposerCancel,
	extensionComposerSubmit,
} from "./extension-submit.js";

const DIR = join(homedir(), ".cursor-mobile");
export const SOCKET_PATH = join(DIR, "composer-bridge.sock");
const STATUS_PATH = join(DIR, "bridge-status.json");

export class ComposerBridgeServer {
	private glass: boolean | null = null;
	private started = false;

	async start(): Promise<void> {
		if (this.started) return;
		this.started = true;
		mkdirSync(DIR, { recursive: true });
		this.glass = await detectGlassMode();
		await ensureBridgeHost();
		await this.writeStatus(true);
		log.info(
			{ glass: this.glass },
			"composer submit via bundled extension insertIntoChat",
		);
	}

	stop(): void {
		this.started = false;
		this.glass = null;
		try {
			writeFileSync(STATUS_PATH, JSON.stringify({ ok: false, at: Date.now() }));
		} catch {
			// ignore
		}
	}

	async bridgeReady(): Promise<boolean> {
		return extensionBridgeAvailable();
	}

	async submit(composerId: string, text: string, _composerName?: string): Promise<void> {
		await ensureBridgeHost();
		if (!(await extensionBridgeAvailable())) {
			throw new Error(
				"Composer bridge is not running. Run: cd cursor-extension && bash install.sh — then restart Cursor (Cmd+Q).",
			);
		}
		log.info({ composerId, len: text.length }, "extension insertIntoChat submit");
		await extensionComposerSubmit(composerId, text);
	}

	async cancel(composerId: string): Promise<void> {
		await extensionComposerCancel(composerId);
	}

	private async writeStatus(ok: boolean, error?: string): Promise<void> {
		const ext = await extensionBridgeAvailable();
		writeFileSync(
			STATUS_PATH,
			JSON.stringify({
				ok,
				socket: ext ? SOCKET_PATH : null,
				pid: process.pid,
				host: ext ? "extension" : "mac-daemon",
				glass: this.glass,
				submitMode: ext ? "insertIntoChat" : "extension-required",
				extensionBridge: ext,
				at: Date.now(),
				...(error ? { error } : {}),
			}),
		);
	}
}

let singleton: ComposerBridgeServer | null = null;

export function getComposerBridgeServer(): ComposerBridgeServer {
	if (!singleton) singleton = new ComposerBridgeServer();
	return singleton;
}
