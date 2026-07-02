/**
 * Filesystem service.
 *
 * Browse, read, write any path the user explicitly allowed.
 * If `fsAllowList` is empty in config we default to allowing `$HOME` and
 * standard project locations under it — explicit deny of system dirs.
 */
import { exec } from "node:child_process";
import { Buffer } from "node:buffer";
import { readFile, readdir, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, normalize, resolve, sep } from "node:path";
import { promisify } from "node:util";
import type { DaemonConfig } from "../config.js";

const execAsync = promisify(exec);

const DENY_PREFIXES = ["/System", "/usr", "/bin", "/sbin", "/etc", "/private", "/Library/Apple"];

export class FilesService {
	constructor(private cfg: DaemonConfig) {}

	private allow(): string[] {
		if (this.cfg.fsAllowList.length) return this.cfg.fsAllowList.map((p) => resolve(p));
		return [homedir()];
	}

	private guard(p: string): string {
		const trimmed = p.trim();
		const rooted =
			trimmed === "" || trimmed === "/"
				? homedir()
				: isAbsolute(trimmed)
					? normalize(trimmed)
					: resolve(this.cfg.defaultCwd ?? homedir(), trimmed);
		const abs = rooted;
		const allow = this.allow();
		const ok = allow.some((root) => abs === root || abs.startsWith(root + sep));
		if (!ok) throw new Error(`path not allowed: ${abs}`);
		if (DENY_PREFIXES.some((pfx) => abs.startsWith(pfx))) {
			throw new Error(`path denied: ${abs}`);
		}
		return abs;
	}

	async list(path: string) {
		const abs = this.guard(path);
		const dirents = await readdir(abs, { withFileTypes: true });
		const entries = await Promise.all(
			dirents
				.filter((d) => !d.name.startsWith(".DS_Store"))
				.map(async (d) => {
					const child = resolve(abs, d.name);
					let size = 0;
					let mtime = 0;
					try {
						const s = await stat(child);
						size = Number(s.size);
						// Round to whole ms; some macOS filesystems return
						// sub-ms precision as a float, which the Android side's
						// strict-typed Long deserializer rejects.
						mtime = Math.floor(s.mtimeMs);
					} catch {
						// ignore broken symlinks etc.
					}
					return {
						name: d.name,
						kind: d.isDirectory() ? "dir" : d.isSymbolicLink() ? "link" : "file",
						size,
						mtime,
					};
				}),
		);
		entries.sort((a, b) => {
			if ((a.kind === "dir") !== (b.kind === "dir")) return a.kind === "dir" ? -1 : 1;
			return a.name.localeCompare(b.name);
		});
		return { path: abs, entries };
	}

	async read(path: string, maxBytes = 1_500_000) {
		const abs = this.guard(path);
		const data = await readFile(abs);
		const truncated = data.byteLength > maxBytes;
		const slice = truncated ? data.subarray(0, maxBytes) : data;
		const looksBinary = slice.includes(0);
		return {
			path: abs,
			contentB64: Buffer.from(slice).toString("base64"),
			encoding: looksBinary ? "binary" : "utf8",
			truncated,
		};
	}

	async write(path: string, contentB64: string) {
		const abs = this.guard(path);
		const data = Buffer.from(contentB64, "base64");
		await writeFile(abs, data);
		return { path: abs, bytes: data.byteLength };
	}

	async openInIde(path: string) {
		const abs = this.guard(path);
		// `cursor` CLI is installed via "Shell command: Install 'cursor' command" in the IDE.
		// We fall back to `open -a Cursor` if it isn't on PATH.
		try {
			await execAsync(`cursor ${JSON.stringify(abs)}`);
		} catch {
			await execAsync(`open -a Cursor ${JSON.stringify(abs)}`);
		}
		return { path: abs };
	}

	async workspaces() {
		// Read Cursor's recent workspaces from its storage.json.
		const storagePaths = [
			`${homedir()}/Library/Application Support/Cursor/User/globalStorage/storage.json`,
			`${homedir()}/Library/Application Support/Cursor/storage.json`,
		];
		const seen = new Set<string>();
		const recent: { path: string; lastOpened?: number }[] = [];
		for (const sp of storagePaths) {
			try {
				const raw = await readFile(sp, "utf8");
				const obj = JSON.parse(raw) as Record<string, unknown>;
				const lists = [
					(obj?.openedPathsList as { workspaces3?: string[] } | undefined)?.workspaces3,
					(obj?.recentFolders as string[] | undefined),
				];
				for (const list of lists) {
					if (!list) continue;
					for (const entry of list) {
						const p = String(entry).replace(/^file:\/\//, "");
						if (!seen.has(p)) {
							seen.add(p);
							recent.push({ path: p });
						}
					}
				}
			} catch {
				// not present
			}
		}
		return { recent, allowed: this.allow(), home: homedir() };
	}
}
