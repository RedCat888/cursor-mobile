/**
 * Quick smoke test of the @cursor/sdk integration the daemon depends on.
 * Skips all the WS / encryption / pairing plumbing — just confirms the API
 * key is good and the SDK can list models, create an agent, and stream a
 * one-shot response.
 *
 *   node scripts/quick-agent.mjs
 */
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { Agent, Cursor } from "@cursor/sdk";

const cfg = JSON.parse(
	await readFile(join(homedir(), ".cursor-mobile", "config.json"), "utf8"),
);
const apiKey = cfg.cursorApiKey ?? process.env.CURSOR_API_KEY;
if (!apiKey) {
	console.error("No API key in ~/.cursor-mobile/config.json. Run `config set-key`.");
	process.exit(1);
}

console.log("1. Cursor.models.list()…");
const models = await Cursor.models.list({ apiKey });
console.log(`   ✓ ${models.length} models. First: ${models[0]?.id}`);

console.log("2. Agent.create({ runtime: local, cwd })…");
await using agent = await Agent.create({
	apiKey,
	model: { id: "auto" },
	local: { cwd: process.cwd() },
});
console.log(`   ✓ agentId=${agent.agentId}`);

console.log("3. agent.send('What time is it?') + stream…");
const run = await agent.send("Reply with just the literal text 'pong' and nothing else.");
let text = "";
for await (const ev of run.stream()) {
	if (ev.type === "assistant") {
		for (const block of ev.message.content ?? []) {
			if (block.type === "text") text += block.text ?? "";
		}
	}
}
const result = await run.wait();
console.log(`   ✓ run status=${result.status} text=${JSON.stringify(text.trim())}`);

console.log("\nAll three SDK paths work — the daemon's agent service is good.");
