/**
 * PairDO — one instance per phone↔Mac pair.
 *
 * Responsibilities:
 *  - Hold the pairing code + handshake state until both sides connect.
 *  - Accept two WebSocket connections (roles: "mac" and "phone").
 *  - Route frames between them; queue when the peer is offline.
 *  - Persist the queue across hibernation in DO storage (sqlite).
 *
 * The DO never inspects encrypted application bodies. It only reads the
 * envelope (`type`, `seq`, `id`) for routing/ordering/dedupe.
 */
type Role = "mac" | "phone";

interface Envelope {
	v: number;
	id: string;
	seq: number;
	ack?: string;
	type: string;
	ts: number;
	body?: unknown;
}

interface PairState {
	code: string;
	createdAt: number;
	macPubKey?: string;
	phonePubKey?: string;
	macLabel?: string;
	phoneLabel?: string;
	paired: boolean;
}

const PAIR_TTL_MS = 5 * 60 * 1000;
const QUEUE_MAX = 1000;
const QUEUE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
/**
 * Response-style frames (anything ending in `.OK` or `.RESULT`) get a much
 * shorter queue lifetime — they're tied to a specific in-flight client request
 * that almost certainly has no listener after 30 seconds. Replaying them on
 * reconnect spams the client with dead acks and wastes bandwidth.
 */
const RESPONSE_TTL_MS = 30 * 1000;
function isResponseType(t: string): boolean {
	return t.endsWith(".OK") || t.endsWith(".RESULT");
}

export class PairDO implements DurableObject {
	private state: DurableObjectState;

	/**
	 * Resolve the currently attached WebSocket for a role by querying the
	 * hibernation API. We *cannot* keep an in-memory map: the DO sleeps
	 * between idle moments and loses any non-storage references.
	 */
	private getSocket(role: Role): WebSocket | undefined {
		return this.state.getWebSockets(role)[0];
	}

	constructor(state: DurableObjectState) {
		this.state = state;
		this.state.blockConcurrencyWhile(async () => {
			const stored = (await this.state.storage.get<PairState>("pair")) ?? null;
			if (!stored) return;
			this.pair = stored;
		});
		this.initSchema();
	}

	private pair: PairState | null = null;

	private initSchema() {
		this.state.storage.sql.exec(`
			CREATE TABLE IF NOT EXISTS queue (
				role TEXT NOT NULL,
				seq INTEGER NOT NULL,
				id TEXT NOT NULL,
				ts INTEGER NOT NULL,
				frame TEXT NOT NULL,
				PRIMARY KEY (role, seq)
			);
			CREATE INDEX IF NOT EXISTS queue_role_ts ON queue (role, ts);
		`);
	}

	async fetch(req: Request): Promise<Response> {
		const url = new URL(req.url);

		if (url.pathname === "/start" && req.method === "POST") {
			const { code } = (await req.json()) as { code: string };
			this.pair = { code, createdAt: Date.now(), paired: false };
			await this.state.storage.put("pair", this.pair);
			await this.state.storage.setAlarm(Date.now() + PAIR_TTL_MS);
			return new Response("ok");
		}

		if (req.headers.get("Upgrade") === "websocket") {
			return this.handleSocket(req);
		}

		return new Response("not found", { status: 404 });
	}

	private async handleSocket(req: Request): Promise<Response> {
		const url = new URL(req.url);
		const role = url.searchParams.get("role") as Role | null;
		if (role !== "mac" && role !== "phone") {
			return new Response("bad role", { status: 400 });
		}

		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];

		// Use hibernation API so we don't burn CPU minutes on idle pairs.
		this.state.acceptWebSocket(server, [role]);

		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
		const role = this.roleOf(ws);
		if (!role) return;

		const text = typeof data === "string" ? data : new TextDecoder().decode(data);
		let env: Envelope;
		try {
			env = JSON.parse(text) as Envelope;
		} catch {
			ws.send(JSON.stringify(errFrame("BAD_JSON")));
			return;
		}

		switch (env.type) {
			case "HELLO":
				await this.onHello(ws, role, env);
				return;
			case "PAIR.CLAIM":
				await this.onPairClaim(ws, role, env);
				return;
			case "PING":
				ws.send(JSON.stringify({ ...env, type: "PONG", ts: Date.now() }));
				return;
			case "ACK":
				await this.onAck(role, env);
				return;
			default:
				// Application frame: route to peer or queue.
				await this.route(role, env);
		}
	}

	private async onHello(ws: WebSocket, role: Role, env: Envelope) {
		const body = (env.body ?? {}) as {
			pubKey?: string;
			deviceLabel?: string;
			resumeFromSeq?: number;
		};
		if (this.pair) {
			if (role === "mac") {
				this.pair.macPubKey ??= body.pubKey;
				this.pair.macLabel ??= body.deviceLabel;
			} else {
				this.pair.phonePubKey ??= body.pubKey;
				this.pair.phoneLabel ??= body.deviceLabel;
			}
			await this.state.storage.put("pair", this.pair);
		}

		const peerRole: Role = role === "mac" ? "phone" : "mac";
		const peer = this.getSocket(peerRole);
		const peerPubKey =
			peerRole === "mac" ? this.pair?.macPubKey : this.pair?.phonePubKey;

		const queued = await this.drainQueue(role, body.resumeFromSeq ?? -1);
		ws.send(
			JSON.stringify({
				v: 1,
				id: crypto.randomUUID(),
				seq: 0,
				type: "HELLO.OK",
				ts: Date.now(),
				body: {
					peerOnline: !!peer && peer.readyState === WebSocket.READY_STATE_OPEN,
					peerPubKey,
					queuedCount: queued.length,
					paired: this.pair?.paired ?? false,
				},
			}),
		);
		for (const frame of queued) ws.send(frame);
	}

	private async onPairClaim(ws: WebSocket, role: Role, env: Envelope) {
		const body = (env.body ?? {}) as { code?: string; pubKey?: string; deviceLabel?: string };
		if (!this.pair || this.pair.paired) {
			ws.send(JSON.stringify(errFrame("PAIR_UNAVAILABLE")));
			return;
		}
		if (body.code !== this.pair.code) {
			ws.send(JSON.stringify(errFrame("PAIR_BAD_CODE")));
			return;
		}
		if (Date.now() - this.pair.createdAt > PAIR_TTL_MS) {
			ws.send(JSON.stringify(errFrame("PAIR_EXPIRED")));
			return;
		}
		if (role === "phone") {
			this.pair.phonePubKey = body.pubKey;
			this.pair.phoneLabel = body.deviceLabel;
		} else {
			this.pair.macPubKey = body.pubKey;
			this.pair.macLabel = body.deviceLabel;
		}
		if (this.pair.macPubKey && this.pair.phonePubKey) {
			this.pair.paired = true;
		}
		await this.state.storage.put("pair", this.pair);

		const okFrame = (forRole: Role) => ({
			v: 1,
			id: crypto.randomUUID(),
			seq: 0,
			type: "PAIR.OK",
			ts: Date.now(),
			body: {
				peerPubKey: forRole === "mac" ? this.pair!.phonePubKey : this.pair!.macPubKey,
				peerLabel: forRole === "mac" ? this.pair!.phoneLabel : this.pair!.macLabel,
				paired: this.pair!.paired,
			},
		});
		ws.send(JSON.stringify(okFrame(role)));
		const peerRole: Role = role === "mac" ? "phone" : "mac";
		const peer = this.getSocket(peerRole);
		if (peer && peer.readyState === WebSocket.READY_STATE_OPEN) {
			peer.send(JSON.stringify(okFrame(peerRole)));
		}
	}

	private async onAck(role: Role, env: Envelope) {
		const ids = ((env.body ?? {}) as { ids?: string[] }).ids ?? [];
		if (!ids.length) return;
		const peerRole: Role = role === "mac" ? "phone" : "mac";
		const placeholders = ids.map(() => "?").join(",");
		this.state.storage.sql.exec(
			`DELETE FROM queue WHERE role = ? AND id IN (${placeholders})`,
			peerRole,
			...ids,
		);
	}

	private async route(senderRole: Role, env: Envelope) {
		const targetRole: Role = senderRole === "mac" ? "phone" : "mac";
		const peer = this.getSocket(targetRole);
		const frame = JSON.stringify(env);

		if (peer && peer.readyState === WebSocket.READY_STATE_OPEN) {
			try {
				peer.send(frame);
				return;
			} catch {
				// Fall through and queue.
			}
		}

		this.state.storage.sql.exec(
			`INSERT OR REPLACE INTO queue (role, seq, id, ts, frame) VALUES (?, ?, ?, ?, ?)`,
			targetRole,
			env.seq,
			env.id,
			env.ts,
			frame,
		);

		// Trim if over the cap. Drop oldest.
		const countRow = this.state.storage.sql
			.exec<{ c: number }>(`SELECT COUNT(*) as c FROM queue WHERE role = ?`, targetRole)
			.one();
		if (countRow.c > QUEUE_MAX) {
			this.state.storage.sql.exec(
				`DELETE FROM queue WHERE role = ? AND seq IN (
					SELECT seq FROM queue WHERE role = ? ORDER BY ts ASC LIMIT ?
				)`,
				targetRole,
				targetRole,
				countRow.c - QUEUE_MAX,
			);
		}
	}

	private async drainQueue(role: Role, sinceSeq: number): Promise<string[]> {
		// Drop response-style frames older than RESPONSE_TTL_MS before replaying.
		const responseCutoff = Date.now() - RESPONSE_TTL_MS;
		this.state.storage.sql.exec(
			`DELETE FROM queue WHERE role = ? AND ts < ? AND
				(frame LIKE '%"type":"%.OK"%' OR frame LIKE '%"type":"%.RESULT"%')`,
			role,
			responseCutoff,
		);
		const rows = this.state.storage.sql
			.exec<{ frame: string }>(
				`SELECT frame FROM queue WHERE role = ? AND seq > ? ORDER BY seq ASC`,
				role,
				sinceSeq,
			)
			.toArray();
		return rows.map((r) => r.frame);
	}

	webSocketClose(_ws: WebSocket): void {
		// Hibernation API tracks sockets for us; nothing to manually unbind.
	}

	webSocketError(_ws: WebSocket): void {
		// Same; the framework removes errored sockets from getWebSockets() automatically.
	}

	private roleOf(ws: WebSocket): Role | null {
		const tags = this.state.getTags(ws);
		if (tags.includes("mac")) return "mac";
		if (tags.includes("phone")) return "phone";
		return null;
	}

	async alarm() {
		// Garbage collect old queued frames + abandoned pairings.
		const cutoff = Date.now() - QUEUE_TTL_MS;
		this.state.storage.sql.exec(`DELETE FROM queue WHERE ts < ?`, cutoff);
		if (this.pair && !this.pair.paired && Date.now() - this.pair.createdAt > PAIR_TTL_MS) {
			this.pair = null;
			await this.state.storage.delete("pair");
		}
		// Re-arm if there's still anything to manage.
		if (this.pair) await this.state.storage.setAlarm(Date.now() + QUEUE_TTL_MS / 7);
	}
}

function errFrame(code: string, message?: string) {
	return {
		v: 1,
		id: crypto.randomUUID(),
		seq: 0,
		type: "ERROR",
		ts: Date.now(),
		body: { code, message: message ?? code },
	};
}
