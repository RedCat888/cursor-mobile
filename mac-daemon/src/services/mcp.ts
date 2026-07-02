/**
 * MCP config service.
 *
 * Reads/writes:
 *   • User scope: ~/.cursor/mcp.json
 *   • Project scope: <projectPath>/.cursor/mcp.json
 *
 * Shape (per Cursor docs):
 *   { "mcpServers": { "<name>": { command?, args?, env?, url?, type?, headers? } } }
 *
 * We also support an `enabled` flag we maintain ourselves (Cursor honors a
 * sibling `disabled` field in newer versions; we mirror to both for safety).
 */
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";

export interface McpServer {
	name: string;
	command?: string;
	args?: string[];
	env?: Record<string, string>;
	url?: string;
	type?: "stdio" | "http" | "sse";
	headers?: Record<string, string>;
	enabled?: boolean;
}

interface McpFile {
	mcpServers?: Record<string, Omit<McpServer, "name">>;
	disabled?: string[];
}

const USER_PATH = join(homedir(), ".cursor", "mcp.json");

export class McpService {
	async list() {
		const user = await this.readFile(USER_PATH);
		return { user, project: {} as Record<string, McpServer[]> };
	}

	async listProject(projectPath: string) {
		const p = join(resolve(projectPath), ".cursor", "mcp.json");
		return this.readFile(p);
	}

	async upsert(scope: "user" | "project", projectPath: string | undefined, server: McpServer) {
		const path = scope === "user" ? USER_PATH : join(resolve(projectPath!), ".cursor", "mcp.json");
		const file = await this.readRaw(path);
		file.mcpServers ??= {};
		const { name, enabled, ...rest } = server;
		file.mcpServers[name] = rest;
		if (enabled === false) {
			file.disabled = Array.from(new Set([...(file.disabled ?? []), name]));
		} else if (enabled === true) {
			file.disabled = (file.disabled ?? []).filter((n) => n !== name);
		}
		await this.writeRaw(path, file);
		return this.readFile(path);
	}

	async remove(scope: "user" | "project", projectPath: string | undefined, name: string) {
		const path = scope === "user" ? USER_PATH : join(resolve(projectPath!), ".cursor", "mcp.json");
		const file = await this.readRaw(path);
		if (file.mcpServers) delete file.mcpServers[name];
		file.disabled = (file.disabled ?? []).filter((n) => n !== name);
		await this.writeRaw(path, file);
		return this.readFile(path);
	}

	async toggle(scope: "user" | "project", projectPath: string | undefined, name: string, enabled: boolean) {
		const path = scope === "user" ? USER_PATH : join(resolve(projectPath!), ".cursor", "mcp.json");
		const file = await this.readRaw(path);
		const set = new Set(file.disabled ?? []);
		if (enabled) set.delete(name);
		else set.add(name);
		file.disabled = [...set];
		await this.writeRaw(path, file);
		return this.readFile(path);
	}

	private async readRaw(path: string): Promise<McpFile> {
		try {
			const raw = await readFile(path, "utf8");
			return JSON.parse(raw) as McpFile;
		} catch (err) {
			if ((err as NodeJS.ErrnoException).code === "ENOENT") return {};
			throw err;
		}
	}

	private async writeRaw(path: string, file: McpFile): Promise<void> {
		await mkdir(dirname(path), { recursive: true });
		await writeFile(path, JSON.stringify(file, null, 2));
	}

	private async readFile(path: string): Promise<McpServer[]> {
		const raw = await this.readRaw(path);
		const disabled = new Set(raw.disabled ?? []);
		const out: McpServer[] = [];
		for (const [name, spec] of Object.entries(raw.mcpServers ?? {})) {
			out.push({ name, ...spec, enabled: !disabled.has(name) });
		}
		out.sort((a, b) => a.name.localeCompare(b.name));
		return out;
	}
}
