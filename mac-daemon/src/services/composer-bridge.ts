/**
 * Composer bridge client — talks to the extension-hosted bridge socket.
 */
import { connect } from "node:net";
import { homedir } from "node:os";
import { join } from "node:path";
import { log } from "../logger.js";

export const SOCKET_PATH = join(homedir(), ".cursor-mobile", "composer-bridge.sock");
const TIMEOUT_MS = 45_000;

interface BridgeResponse {
	ok: boolean;
	error?: string;
	version?: string;
	host?: string;
	submitMode?: string;
}

function callBridge(req: Record<string, unknown>): Promise<BridgeResponse> {
	return new Promise((resolve, reject) => {
		const socket = connect(SOCKET_PATH);
		let buf = "";
		const timer = setTimeout(() => {
			socket.destroy();
			reject(
				new Error(
					"composer bridge timeout — is Cursor running with the bundled bridge extension? " +
						"(cd cursor-extension && bash install.sh, then Cmd+Q restart Cursor)",
				),
			);
		}, TIMEOUT_MS);

		socket.on("error", (err) => {
			clearTimeout(timer);
			reject(
				new Error(
					`composer bridge unavailable (${(err as NodeJS.ErrnoException).code ?? err.message}). ` +
						"Install: cd cursor-extension && bash install.sh — restart Cursor (Cmd+Q).",
				),
			);
		});

		socket.on("connect", () => {
			socket.write(`${JSON.stringify(req)}\n`);
		});

		socket.on("data", (chunk) => {
			buf += chunk.toString("utf8");
			const nl = buf.indexOf("\n");
			if (nl < 0) return;
			clearTimeout(timer);
			socket.end();
			try {
				resolve(JSON.parse(buf.slice(0, nl).trim()) as BridgeResponse);
			} catch {
				reject(new Error("invalid bridge response"));
			}
		});
	});
}

export async function bridgeAvailable(): Promise<boolean> {
	try {
		const resp = await bridgePing();
		return resp.ok;
	} catch {
		return false;
	}
}

export async function bridgePing(): Promise<BridgeResponse> {
	return callBridge({ op: "ping" });
}

export async function bridgeSubmit(
	composerId: string,
	text: string,
	composerName?: string,
): Promise<void> {
	log.info({ composerId, len: text.length }, "bridge submit");
	const resp = await callBridge({ op: "submit", composerId, text, composerName });
	if (!resp.ok) {
		throw new Error(resp.error ?? "bridge submit failed");
	}
}

export async function bridgeCancel(composerId: string): Promise<void> {
	const resp = await callBridge({ op: "cancel", composerId });
	if (!resp.ok) {
		throw new Error(resp.error ?? "bridge cancel failed");
	}
}
