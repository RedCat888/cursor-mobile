import * as fs from "node:fs";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import * as vscode from "vscode";

const BRIDGE_VERSION = "0.2.1";
const SOCKET_PATH = path.join(os.homedir(), ".cursor-mobile", "composer-bridge.sock");
const STATUS_PATH = path.join(os.homedir(), ".cursor-mobile", "bridge-status.json");

interface BridgeRequest {
	op: "ping" | "submit" | "status" | "cancel";
	composerId?: string;
	text?: string;
}

interface BridgeResponse {
	ok: boolean;
	error?: string;
	version?: string;
	host?: string;
	submitMode?: string;
}

export async function submitToComposer(composerId: string, text: string): Promise<void> {
	const id = composerId.trim();
	const message = text.trim();
	if (!id || !message) throw new Error("composerId and text are required");

	// Runs in workbench context (install.sh workbench patch) — extension host cannot call ComposerService.insertIntoChat.
	await vscode.commands.executeCommand("cursorMobileBridge.submit", id, message);
}

async function cancelComposer(composerId: string): Promise<void> {
	const id = composerId.trim();
	if (!id) throw new Error("composerId is required");
	await vscode.commands.executeCommand("composer.cancelComposerStep", id);
}

async function handleRequest(req: BridgeRequest): Promise<BridgeResponse> {
	switch (req.op) {
		case "ping":
		case "status":
			return { ok: true, version: BRIDGE_VERSION, host: "extension", submitMode: "cursorMobileBridge.submit" };
		case "submit": {
			if (!req.composerId?.trim() || !req.text?.trim()) {
				throw new Error("composerId and text are required");
			}
			await submitToComposer(req.composerId.trim(), req.text);
			return { ok: true, version: BRIDGE_VERSION, host: "extension" };
		}
		case "cancel": {
			const id = req.composerId?.trim();
			if (!id) throw new Error("composerId is required");
			await cancelComposer(id);
			return { ok: true, version: BRIDGE_VERSION, host: "extension" };
		}
		default:
			throw new Error(`unknown op: ${(req as BridgeRequest).op}`);
	}
}

export function activate(context: vscode.ExtensionContext): void {
	const log = vscode.window.createOutputChannel("Cursor Mobile Bridge");
	log.appendLine(`[${new Date().toISOString()}] activating bridge ${BRIDGE_VERSION}`);

	fs.mkdirSync(path.dirname(SOCKET_PATH), { recursive: true });
	try {
		fs.unlinkSync(SOCKET_PATH);
	} catch {
		// no stale socket
	}

	const server = net.createServer((socket) => {
		let buf = "";
		socket.on("data", (chunk) => {
			buf += chunk.toString("utf8");
			let nl: number;
			while ((nl = buf.indexOf("\n")) >= 0) {
				const line = buf.slice(0, nl).trim();
				buf = buf.slice(nl + 1);
				if (!line) continue;
				void (async () => {
					try {
						const req = JSON.parse(line) as BridgeRequest;
						const resp = await handleRequest(req);
						socket.write(`${JSON.stringify(resp)}\n`);
					} catch (err) {
						const resp: BridgeResponse = {
							ok: false,
							error: err instanceof Error ? err.message : String(err),
						};
						socket.write(`${JSON.stringify(resp)}\n`);
					}
				})();
			}
		});
	});

	server.listen(SOCKET_PATH, () => {
		log.appendLine(`listening on ${SOCKET_PATH}`);
		fs.writeFileSync(
			STATUS_PATH,
			JSON.stringify({
				ok: true,
				socket: SOCKET_PATH,
				host: "extension",
				submitMode: "cursorMobileBridge.submit",
				version: BRIDGE_VERSION,
				pid: process.pid,
				at: Date.now(),
			}),
		);
	});
	server.on("error", (err) => {
		log.appendLine(`server error: ${err.message}`);
		fs.writeFileSync(
			STATUS_PATH,
			JSON.stringify({ ok: false, error: err.message, at: Date.now() }),
		);
	});

	context.subscriptions.push(
		log,
		vscode.commands.registerCommand("cursorMobileBridge.submitToComposer", async () => {
			const composerId = await vscode.window.showInputBox({
				prompt: "Composer ID",
				placeHolder: "b778fbc7-855c-4e9a-9925-4229ba4256e8",
			});
			const text = await vscode.window.showInputBox({ prompt: "Message text" });
			if (!composerId || !text) return;
			await submitToComposer(composerId, text);
			vscode.window.showInformationMessage("Submitted to IDE composer.");
		}),
		vscode.commands.registerCommand("cursorMobileBridge.status", () => {
			vscode.window.showInformationMessage(`Cursor Mobile bridge active on ${SOCKET_PATH}`);
		}),
		{
			dispose: () => {
				server.close();
				try {
					fs.unlinkSync(SOCKET_PATH);
				} catch {
					// ignore
				}
				try {
					unlinkSync(STATUS_PATH);
				} catch {
					// ignore
				}
			},
		},
	);

	void vscode.window.setStatusBarMessage("Cursor Mobile bridge active", 8000);
}

function unlinkSync(p: string): void {
	try {
		fs.unlinkSync(p);
	} catch {
		// ignore
	}
}

export function deactivate(): void {}
