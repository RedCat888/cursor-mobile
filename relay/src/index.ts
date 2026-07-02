/**
 * Cursor-Mobile relay Worker.
 *
 * Acts as a thin message router between a Mac daemon and an Android client,
 * keyed by a pairing ID. All routing happens inside the PairDO Durable Object
 * so both sides hit the same instance worldwide.
 *
 * Endpoints:
 *   GET  /health                       — liveness probe
 *   POST /pair/start                   — Mac asks for a pairing slot, gets { pairId, code }
 *   GET  /pair/:pairId/ws?role=…       — both sides upgrade to WebSocket and talk via PairDO
 */
export { PairDO } from "./PairDO";

export interface Env {
	PAIR_DO: DurableObjectNamespace;
}

export default {
	async fetch(req: Request, env: Env): Promise<Response> {
		const url = new URL(req.url);

		if (url.pathname === "/health") {
			return json({ ok: true, ts: Date.now() });
		}

		if (url.pathname === "/pair/start" && req.method === "POST") {
			// Mac daemon kicks off pairing. We mint a fresh DO id so the daemon
			// and phone end up on the same instance. The 6-letter code is
			// short enough to type or QR-scan; entropy is fine because the
			// pairing window is < 5 minutes and rate-limited per DO.
			const pairId = crypto.randomUUID();
			const code = generatePairCode();
			const stub = env.PAIR_DO.get(env.PAIR_DO.idFromName(pairId));
			await stub.fetch("https://do/start", {
				method: "POST",
				body: JSON.stringify({ code }),
			});
			return json({ pairId, code, ttlSeconds: 300 });
		}

		const wsMatch = url.pathname.match(/^\/pair\/([^/]+)\/ws$/);
		if (wsMatch) {
			const pairId = wsMatch[1]!;
			const stub = env.PAIR_DO.get(env.PAIR_DO.idFromName(pairId));
			return stub.fetch(req);
		}

		return new Response("not found", { status: 404 });
	},
} satisfies ExportedHandler<Env>;

function json(data: unknown, init: ResponseInit = {}): Response {
	return new Response(JSON.stringify(data), {
		...init,
		headers: { "content-type": "application/json", ...(init.headers ?? {}) },
	});
}

/**
 * 6 character pairing code. Avoids visually ambiguous glyphs (0/O, 1/I/L).
 * 32^6 ≈ 10^9, paired with a 5-minute TTL and per-DO rate limit, this is
 * brute-force resistant for the pairing window.
 */
function generatePairCode(): string {
	const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	const bytes = crypto.getRandomValues(new Uint8Array(6));
	let out = "";
	for (const b of bytes) out += alphabet[b % alphabet.length];
	return out;
}
