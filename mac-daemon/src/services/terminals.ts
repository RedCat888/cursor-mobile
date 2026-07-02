/**
 * PTY service — phone-attached terminals on the Mac.
 *
 * Sessions outlive WebSocket reconnects. We keep a per-PTY ring buffer so a
 * phone that reconnects can replay everything since a given `seq`.
 *
 * node-pty is the only native dep; on first install it builds via node-gyp.
 */
import { Buffer } from "node:buffer";
import { homedir } from "node:os";
import * as pty from "node-pty";
import { ulid } from "ulid";
import type { DaemonConfig } from "../config.js";
import { log } from "../logger.js";

interface Session {
	ptyId: string;
	pid: number;
	shell: string;
	cwd: string;
	cols: number;
	rows: number;
	proc: pty.IPty;
	buffer: { seq: number; b64: string }[];
	nextSeq: number;
	exitCode?: number;
}

const RING_BYTES_MAX = 256 * 1024;

export interface TerminalEmit {
	stdout: (msg: { ptyId: string; dataB64: string; seq: number }) => void;
	exit: (msg: { ptyId: string; code: number }) => void;
}

export class TerminalService {
	private sessions = new Map<string, Session>();

	constructor(private cfg: DaemonConfig, private emit: TerminalEmit) {}

	open(args: { cwd?: string; cols?: number; rows?: number; shell?: string }) {
		const shell = args.shell ?? process.env.SHELL ?? "/bin/zsh";
		const cwd = args.cwd ?? this.cfg.defaultCwd ?? homedir();
		const cols = args.cols ?? 100;
		const rows = args.rows ?? 30;

		const proc = pty.spawn(shell, [], {
			name: "xterm-256color",
			cols,
			rows,
			cwd,
			env: { ...process.env, TERM: "xterm-256color", CURSOR_MOBILE: "1" },
		});

		const session: Session = {
			ptyId: `pty_${ulid()}`,
			pid: proc.pid,
			shell,
			cwd,
			cols,
			rows,
			proc,
			buffer: [],
			nextSeq: 1,
		};
		this.sessions.set(session.ptyId, session);
		log.info({ ptyId: session.ptyId, shell, cwd, pid: proc.pid }, "pty spawned");

		proc.onData((data) => {
			const buf = Buffer.from(data, "utf8");
			const b64 = buf.toString("base64");
			const seq = session.nextSeq++;
			session.buffer.push({ seq, b64 });
			let total = session.buffer.reduce((n, e) => n + e.b64.length, 0);
			while (total > RING_BYTES_MAX && session.buffer.length > 1) {
				const dropped = session.buffer.shift()!;
				total -= dropped.b64.length;
			}
			this.emit.stdout({ ptyId: session.ptyId, dataB64: b64, seq });
		});

		proc.onExit(({ exitCode }) => {
			session.exitCode = exitCode;
			log.info({ ptyId: session.ptyId, exitCode }, "pty exited");
			this.emit.exit({ ptyId: session.ptyId, code: exitCode });
			// Keep session around briefly so phone can read the tail.
			setTimeout(() => this.sessions.delete(session.ptyId), 30_000);
		});

		return {
			ptyId: session.ptyId,
			pid: session.pid,
			shell,
			cwd,
		};
	}

	stdin(ptyId: string, dataB64: string) {
		const s = this.sessions.get(ptyId);
		if (!s) return;
		const buf = Buffer.from(dataB64, "base64");
		s.proc.write(buf.toString("utf8"));
	}

	resize(ptyId: string, cols: number, rows: number) {
		const s = this.sessions.get(ptyId);
		if (!s) return;
		s.cols = cols;
		s.rows = rows;
		s.proc.resize(cols, rows);
	}

	close(ptyId: string) {
		const s = this.sessions.get(ptyId);
		if (!s) return;
		try {
			s.proc.kill();
		} catch (err) {
			log.warn({ err }, "kill pty failed");
		}
		this.sessions.delete(ptyId);
	}

	list() {
		return {
			sessions: [...this.sessions.values()].map((s) => ({
				ptyId: s.ptyId,
				cwd: s.cwd,
				shell: s.shell,
				lastSeq: s.nextSeq - 1,
				bufferPreview: s.buffer
					.slice(-5)
					.map((e) => Buffer.from(e.b64, "base64").toString("utf8"))
					.join(""),
				exited: s.exitCode !== undefined,
			})),
		};
	}

	attach(ptyId: string, sinceSeq: number) {
		const s = this.sessions.get(ptyId);
		if (!s) return [];
		return s.buffer.filter((e) => e.seq > sinceSeq);
	}
}
