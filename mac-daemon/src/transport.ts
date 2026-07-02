/**
 * Transport layer.
 *
 * Two transports converge into a single Channel facade that the Router speaks to:
 *   • RelayTransport — outbound WebSocket to the Cloudflare relay (works on cellular).
 *   • LanTransport   — inbound WebSocket server, used when the phone is on the same Wi-Fi.
 *
 * The Channel exposes:
 *   • `onMessage(handler)` — typed envelopes after decryption.
 *   • `send(env)` — encrypts and writes; queues locally if disconnected.
 *   • `connected()` — boolean for UI/status.
 */
import { Buffer } from "node:buffer";
import { EventEmitter } from "node:events";
import { createServer, type Server } from "node:http";
import { ulid } from "ulid";
import { WebSocket, WebSocketServer, type RawData } from "ws";
import type { DaemonConfig } from "./config.js";
import { open, seal, type SealedBody } from "./crypto.js";
import { log } from "./logger.js";
import { PROTOCOL_VERSION, type Envelope, type MessageType } from "./protocol.js";

const CONTROL_TYPES: ReadonlySet<MessageType> = new Set<MessageType>([
	"HELLO",
	"HELLO.OK",
	"PAIR.REQUEST",
	"PAIR.CLAIM",
	"PAIR.OK",
	"ACK",
	"PING",
	"PONG",
	"ERROR",
]);

export interface ChannelOptions {
	config: DaemonConfig;
	sharedKey: Uint8Array | null;
	deviceLabel: string;
}

export class Channel extends EventEmitter {
	private nextSeq = 1;
	private outbox = new Map<string, Envelope>();
	private sockets = new Set<WebSocket>();
	private relay?: WebSocket;
	private lan?: WebSocketServer;
	private lanServer?: Server;
	private relayReconnectTimer?: NodeJS.Timeout;
	private peerHighestSeq = -1;

	constructor(private opts: ChannelOptions) {
		super();
	}

	/** Bring all configured transports online. */
	async start(): Promise<void> {
		if (this.opts.config.lan.enabled) this.startLan();
		if (this.opts.config.pairing) this.startRelay();
	}

	async stop(): Promise<void> {
		clearTimeout(this.relayReconnectTimer);
		for (const ws of this.sockets) ws.close();
		this.sockets.clear();
		this.relay?.close();
		this.lan?.close();
		await new Promise<void>((res) => this.lanServer?.close(() => res()) ?? res());
	}

	connected(): boolean {
		return [...this.sockets].some((s) => s.readyState === WebSocket.OPEN);
	}

	/** Send a typed envelope to the phone. Wraps body in encryption when needed. */
	send<T>(type: MessageType, body: T, opts?: { ack?: string }): Envelope {
		const env: Envelope = {
			v: PROTOCOL_VERSION,
			id: ulid(),
			seq: this.nextSeq++,
			ack: opts?.ack,
			type,
			ts: Date.now(),
			body: this.maybeEncrypt(type, body, opts?.ack),
		};
		this.outbox.set(env.id, env);
		this.broadcast(env);
		return env;
	}

	private maybeEncrypt(type: MessageType, body: unknown, ack?: string): unknown {
		if (CONTROL_TYPES.has(type)) return body;
		const key = this.opts.sharedKey;
		if (!key) return body;
		const aad = `${type}|${ack ?? ""}`;
		return seal(key, body, aad);
	}

	private isSealedBody(body: unknown): body is SealedBody {
		return (
			body != null &&
			typeof body === "object" &&
			typeof (body as SealedBody).n === "string" &&
			typeof (body as SealedBody).c === "string"
		);
	}

	private maybeDecrypt(env: Envelope): Envelope {
		if (CONTROL_TYPES.has(env.type)) return env;
		const key = this.opts.sharedKey;
		if (!key || env.body == null) return env;
		if (!this.isSealedBody(env.body)) return env;
		try {
			const plain = open(key, env.body, `${env.type}|${env.ack ?? ""}`);
			return { ...env, body: plain };
		} catch (err) {
			log.warn({ err, id: env.id }, "decrypt failed; passing through");
			return env;
		}
	}

	private broadcast(env: Envelope) {
		const wire = JSON.stringify(env);
		for (const ws of this.sockets) {
			if (ws.readyState === WebSocket.OPEN) {
				try {
					ws.send(wire);
				} catch (err) {
					log.warn({ err }, "broadcast send failed");
				}
			}
		}
	}

	// --- LAN transport -----------------------------------------------------

	private startLan() {
		const { port, bindHost } = this.opts.config.lan;
		this.lanServer = createServer();
		this.lan = new WebSocketServer({ server: this.lanServer, path: "/lan" });
		this.lan.on("connection", (ws, req) => {
			log.info({ remote: req.socket.remoteAddress }, "LAN client connected");
			this.attachSocket(ws);
		});
		this.lanServer.listen(port, bindHost, () => {
			log.info({ port, bindHost }, "LAN server listening");
		});
	}

	// --- Relay transport ---------------------------------------------------

	private startRelay() {
		const pairing = this.opts.config.pairing;
		if (!pairing) return;
		const wsUrl = this.opts.config.relayUrl.replace(/^http/, "ws") +
			`/pair/${encodeURIComponent(pairing.pairId)}/ws?role=mac`;
		log.info({ url: wsUrl }, "connecting to relay");
		const ws = new WebSocket(wsUrl);

		ws.on("open", () => {
			log.info("relay connected");
			ws.send(
				JSON.stringify(<Envelope>{
					v: PROTOCOL_VERSION,
					id: ulid(),
					seq: 0,
					type: "HELLO",
					ts: Date.now(),
					body: {
						role: "mac",
						pairId: pairing.pairId,
						pubKey: pairing.ourPubKey,
						deviceLabel: this.opts.deviceLabel,
						resumeFromSeq: this.peerHighestSeq,
					},
				}),
			);
			this.attachSocket(ws);
		});

		ws.on("close", () => {
			log.warn("relay connection closed; reconnecting in 3s");
			this.sockets.delete(ws);
			this.relay = undefined;
			this.relayReconnectTimer = setTimeout(() => this.startRelay(), 3000);
		});
		ws.on("error", (err) => log.warn({ err: err.message }, "relay socket error"));

		this.relay = ws;
	}

	private attachSocket(ws: WebSocket) {
		this.sockets.add(ws);
		ws.on("message", (data: RawData) => this.handleRaw(ws, data));
		ws.on("close", () => this.sockets.delete(ws));

		// Replay anything we previously emitted that we still hold in outbox.
		// (Outbox is trimmed by ACK; the relay also queues, but a freshly
		// reconnected phone benefits from the daemon's local copy.)
		for (const env of this.outbox.values()) {
			try {
				ws.send(JSON.stringify(env));
			} catch {
				// peer not ready; ignore
			}
		}
	}

	private handleRaw(ws: WebSocket, data: RawData) {
		let env: Envelope;
		try {
			const text = typeof data === "string" ? data : Buffer.isBuffer(data) ? data.toString("utf8") : Buffer.from(data as ArrayBuffer).toString("utf8");
			env = JSON.parse(text) as Envelope;
		} catch (err) {
			log.warn({ err }, "bad inbound JSON");
			return;
		}

		if (env.seq > this.peerHighestSeq) this.peerHighestSeq = env.seq;

		// Ack inbound (lets the relay drop queued messages).
		if (env.type !== "ACK" && env.type !== "PING" && env.type !== "PONG") {
			try {
				ws.send(
					JSON.stringify(<Envelope>{
						v: PROTOCOL_VERSION,
						id: ulid(),
						seq: this.nextSeq++,
						type: "ACK",
						ts: Date.now(),
						body: { ids: [env.id] },
					}),
				);
			} catch {
				// best-effort
			}
		}

		// HELLO.OK / PAIR.OK control frames bubble up so callers can react.
		const decoded = this.maybeDecrypt(env);

		// Drop our own ACKs of received messages from outbox.
		if (decoded.type === "ACK") {
			const ids = ((decoded.body ?? {}) as { ids?: string[] }).ids ?? [];
			for (const id of ids) this.outbox.delete(id);
			return;
		}

		this.emit("message", decoded);
	}

	/** Replace the shared key (e.g. after pairing completes mid-session). */
	rotateKey(key: Uint8Array | null) {
		this.opts.sharedKey = key;
	}
}
