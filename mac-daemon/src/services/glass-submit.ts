/**
 * Submit user messages into Cursor IDE Composer without a third-party extension.
 *
 * Each submit launches Cursor Mobile Submit.app via `open -W` so macOS applies the
 * user's Accessibility grant (launchd background processes do not get that grant).
 */
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { log } from "../logger.js";

const execFileAsync = promisify(execFile);
const CURSOR_BIN = "/Applications/Cursor.app/Contents/Resources/app/bin/cursor";
const MOBILE_DIR = join(homedir(), ".cursor-mobile");
const SUBMIT_HELPER_APP = join(homedir(), "Applications", "Cursor Mobile Submit.app");
const TIMEOUT_MS = 45_000;

interface HelperResponse {
	ok: boolean;
	error?: string;
	accessibility?: boolean;
}

function permissionHelp(): string {
	return (
		"macOS blocked keyboard injection. Enable **Cursor Mobile Submit** in " +
		"System Settings → Privacy & Security → Accessibility " +
		`(app: ${SUBMIT_HELPER_APP}). Automation is NOT required.`
	);
}

function parsePermissionError(err: unknown): string | null {
	const msg = err instanceof Error ? err.message : String(err);
	const lower = msg.toLowerCase();
	if (
		lower.includes("1002") ||
		lower.includes("not allowed to send keystrokes") ||
		lower.includes("keyboard injection") ||
		lower.includes("assistive access")
	) {
		return permissionHelp();
	}
	return null;
}

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

async function invokeHelper(args: string[], resultPath?: string): Promise<void> {
	mkdirSync(MOBILE_DIR, { recursive: true });
	if (resultPath) {
		try {
			unlinkSync(resultPath);
		} catch {
			// no stale result
		}
	}

	await execFileAsync("/usr/bin/open", ["-n", SUBMIT_HELPER_APP, "--args", ...args]);

	if (!resultPath) {
		await sleep(500);
		return;
	}

	const deadline = Date.now() + TIMEOUT_MS;
	while (Date.now() < deadline) {
		if (existsSync(resultPath)) {
			await sleep(30);
			return;
		}
		await sleep(50);
	}
	throw new Error("submit helper timeout — is Cursor Mobile Submit.app installed?");
}

async function callSubmitHelper(req: Record<string, unknown>): Promise<HelperResponse> {
	const id = randomUUID();
	const reqPath = join(MOBILE_DIR, `submit-req-${id}.json`);
	const resPath = `${reqPath}.result`;

	writeFileSync(reqPath, JSON.stringify(req));

	try {
		await invokeHelper(["--submit-once", reqPath], resPath);
		const raw = readFileSync(resPath, "utf8").trim();
		return JSON.parse(raw) as HelperResponse;
	} finally {
		for (const p of [reqPath, resPath]) {
			try {
				unlinkSync(p);
			} catch {
				// ignore cleanup errors
			}
		}
	}
}

export async function detectGlassMode(): Promise<boolean> {
	try {
		const { stdout } = await execFileAsync(CURSOR_BIN, ["--status"], {
			timeout: 8_000,
			maxBuffer: 512_000,
		});
		return stdout.includes("(Cursor Agents)");
	} catch {
		return false;
	}
}

export async function submitHelperReady(): Promise<boolean> {
	const id = randomUUID();
	const resPath = join(MOBILE_DIR, `submit-ping-${id}.result`);
	try {
		await invokeHelper(["--ping-once", resPath], resPath);
		const raw = readFileSync(resPath, "utf8").trim();
		const resp = JSON.parse(raw) as HelperResponse;
		return resp.ok === true && resp.accessibility === true;
	} catch {
		return false;
	} finally {
		try {
			unlinkSync(resPath);
		} catch {
			// ignore
		}
	}
}

export async function nativeComposerSubmit(
	composerId: string,
	text: string,
	composerName?: string,
): Promise<void> {
	if (!text.trim()) throw new Error("empty message");

	log.info({ composerId, len: text.length, composerName }, "native composer submit");

	try {
		const resp = await callSubmitHelper({
			op: "submit",
			composerId,
			text,
			composerName,
		});
		if (!resp.ok) {
			const hint = parsePermissionError(new Error(resp.error ?? ""));
			throw new Error(hint ?? resp.error ?? "submit helper failed");
		}
		log.info({ composerId }, "native composer submit ok");
	} catch (err) {
		const hint = parsePermissionError(err);
		if (hint) throw new Error(hint);
		throw err;
	}
}

async function callCancelHelper(): Promise<HelperResponse> {
	const id = randomUUID();
	const resPath = join(MOBILE_DIR, `cancel-${id}.result`);
	try {
		await invokeHelper(["--cancel-once", resPath], resPath);
		const raw = readFileSync(resPath, "utf8").trim();
		return JSON.parse(raw) as HelperResponse;
	} finally {
		try {
			unlinkSync(resPath);
		} catch {
			// ignore
		}
	}
}

export async function nativeComposerCancel(_composerId: string): Promise<void> {
	log.info("native composer cancel");
	try {
		const resp = await callCancelHelper();
		if (!resp.ok) {
			const hint = parsePermissionError(new Error(resp.error ?? ""));
			throw new Error(hint ?? resp.error ?? "cancel helper failed");
		}
	} catch (err) {
		const hint = parsePermissionError(err);
		if (hint) throw new Error(hint);
		throw err;
	}
}

export { permissionHelp as accessibilityHelp, SUBMIT_HELPER_APP };
