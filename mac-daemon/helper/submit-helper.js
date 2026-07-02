#!/usr/bin/env node
/**
 * Accessibility helper — runs inside CursorMobileSubmit.app so macOS grants
 * keystroke permission to the app bundle (not bare Homebrew node).
 */
import { createServer } from "node:net";
import { execFile, spawn } from "node:child_process";
import { unlinkSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const SOCKET = join(homedir(), ".cursor-mobile", "submit-helper.sock");
const STATUS = join(homedir(), ".cursor-mobile", "submit-helper-status.json");
const SUBMIT_APP = join(homedir(), ".cursor-mobile", "CursorMobileSubmit.app");

function sleep(ms) {
	return new Promise((r) => setTimeout(r, ms));
}

async function pbcopy(text) {
	await new Promise((resolve, reject) => {
		const child = spawn("pbcopy", [], { stdio: ["pipe", "ignore", "pipe"] });
		let err = "";
		child.stderr.on("data", (d) => {
			err += d.toString();
		});
		child.on("error", reject);
		child.on("close", (code) => {
			if (code === 0) resolve();
			else reject(new Error(err || `pbcopy exited ${code}`));
		});
		child.stdin.write(text);
		child.stdin.end();
	});
}

async function runAppleScript(script) {
	await execFileAsync("osascript", ["-"], { input: script, timeout: 30_000 });
}

function escapeApple(s) {
	return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function accessibilityError(err) {
	const msg = err instanceof Error ? err.message : String(err);
	if (msg.includes("1002") || msg.includes("not allowed to send keystrokes")) {
		return (
			"macOS blocked keystrokes (1002). Enable **Cursor Mobile Submit** in " +
			"System Settings → Privacy & Security → Accessibility " +
			`(add ${SUBMIT_APP} if missing).`
		);
	}
	return msg;
}

async function focusAgentByName(composerName) {
	const name = escapeApple(composerName);
	await runAppleScript(`tell application "Cursor" to activate
delay 0.4
tell application "System Events"
  if not (exists process "Cursor") then return
  tell process "Cursor"
    set agentName to "${name}"
    repeat with w in windows
      try
        set uiElts to entire contents of w
        repeat with e in uiElts
          try
            if name of e contains agentName then
              perform action "AXPress" of e
              return
            end if
          end try
        end repeat
      end try
    end repeat
  end tell
end tell`);
}

async function pasteAndSubmit() {
	await runAppleScript(`tell application "Cursor" to activate
delay 0.25
tell application "System Events"
  tell process "Cursor"
    keystroke "v" using command down
    delay 0.15
    keystroke return
  end tell
end tell`);
}

async function submit({ composerId, text, composerName }) {
	if (!text?.trim()) throw new Error("empty message");
	await pbcopy(text);
	await execFileAsync("open", ["-a", "Cursor"], { timeout: 5_000 });
	await sleep(400);
	if (composerName?.trim()) {
		try {
			await focusAgentByName(composerName.trim());
			await sleep(350);
		} catch {
			// best-effort focus
		}
	}
	await pasteAndSubmit();
	return { ok: true, composerId };
}

function writeStatus(extra = {}) {
	writeFileSync(
		STATUS,
		JSON.stringify({ ok: true, socket: SOCKET, pid: process.pid, at: Date.now(), ...extra }),
	);
}

try {
	unlinkSync(SOCKET);
} catch {
	// no stale socket
}

const server = createServer((socket) => {
	let buf = "";
	socket.on("data", (chunk) => {
		buf += chunk.toString("utf8");
		let nl;
		while ((nl = buf.indexOf("\n")) >= 0) {
			const line = buf.slice(0, nl).trim();
			buf = buf.slice(nl + 1);
			if (!line) continue;
			void (async () => {
				try {
					const req = JSON.parse(line);
					if (req.op === "ping") {
						socket.write(`${JSON.stringify({ ok: true })}\n`);
						return;
					}
					if (req.op === "submit") {
						const resp = await submit(req);
						socket.write(`${JSON.stringify(resp)}\n`);
						return;
					}
					throw new Error(`unknown op: ${req.op}`);
				} catch (err) {
					socket.write(`${JSON.stringify({ ok: false, error: accessibilityError(err) })}\n`);
				}
			})();
		}
	});
});

server.listen(SOCKET, () => {
	writeStatus();
	console.log(`submit-helper listening on ${SOCKET}`);
});

process.on("SIGTERM", () => {
	server.close();
	try {
		unlinkSync(SOCKET);
	} catch {
		// ignore
	}
	process.exit(0);
});
