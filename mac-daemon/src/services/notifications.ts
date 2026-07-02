/**
 * Push notification dispatcher (FCM HTTP v1).
 *
 * Used so your phone gets a heads-up the moment a long-running agent
 * finishes, asks a question, or errors — even when the app is killed.
 *
 * The user supplies a Firebase service account key in config; we mint
 * short-lived OAuth tokens on demand.
 *
 * If FCM isn't configured (no key), we no-op silently and the daemon still
 * works in foreground / via the open WS connection.
 */
import { createHmac, createPrivateKey, sign } from "node:crypto";
import { Buffer } from "node:buffer";
import type { DaemonConfig } from "../config.js";
import { log } from "../logger.js";

interface FcmServiceAccount {
	client_email: string;
	private_key: string;
	project_id: string;
}

export interface Notification {
	title: string;
	body: string;
	data?: Record<string, string>;
	tag?: string;
}

export class NotificationsService {
	private tokenCache?: { token: string; exp: number };
	private serviceAccount?: FcmServiceAccount;

	constructor(private cfg: DaemonConfig) {
		const raw = this.cfg.fcm.serverKey;
		if (!raw) return;
		try {
			this.serviceAccount = JSON.parse(raw) as FcmServiceAccount;
		} catch {
			log.warn("FCM serverKey is not valid JSON; push disabled");
		}
	}

	enabled(): boolean {
		return !!this.serviceAccount && !!this.cfg.fcm.deviceToken;
	}

	async send(n: Notification): Promise<void> {
		if (!this.enabled()) return;
		const token = await this.accessToken();
		if (!token) return;
		const projectId = this.cfg.fcm.projectId ?? this.serviceAccount!.project_id;
		const res = await fetch(
			`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
			{
				method: "POST",
				headers: {
					authorization: `Bearer ${token}`,
					"content-type": "application/json",
				},
				body: JSON.stringify({
					message: {
						token: this.cfg.fcm.deviceToken,
						notification: { title: n.title, body: n.body },
						android: {
							priority: "HIGH",
							notification: {
								channel_id: "cursor_mobile_agents",
								tag: n.tag,
							},
						},
						data: n.data,
					},
				}),
			},
		);
		if (!res.ok) {
			log.warn({ status: res.status, body: await res.text() }, "FCM send failed");
		}
	}

	registerToken(token: string) {
		this.cfg.fcm.deviceToken = token;
	}

	private async accessToken(): Promise<string | null> {
		if (this.tokenCache && this.tokenCache.exp > Date.now() + 60_000) {
			return this.tokenCache.token;
		}
		if (!this.serviceAccount) return null;
		const now = Math.floor(Date.now() / 1000);
		const header = b64url(Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })));
		const payload = b64url(
			Buffer.from(
				JSON.stringify({
					iss: this.serviceAccount.client_email,
					scope: "https://www.googleapis.com/auth/firebase.messaging",
					aud: "https://oauth2.googleapis.com/token",
					exp: now + 3600,
					iat: now,
				}),
			),
		);
		const toSign = `${header}.${payload}`;
		const key = createPrivateKey(this.serviceAccount.private_key);
		const signature = b64url(sign("RSA-SHA256", Buffer.from(toSign), key));
		const jwt = `${toSign}.${signature}`;

		const res = await fetch("https://oauth2.googleapis.com/token", {
			method: "POST",
			headers: { "content-type": "application/x-www-form-urlencoded" },
			body: new URLSearchParams({
				grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
				assertion: jwt,
			}),
		});
		if (!res.ok) {
			log.warn({ status: res.status }, "fcm oauth failed");
			return null;
		}
		const json = (await res.json()) as { access_token: string; expires_in: number };
		this.tokenCache = {
			token: json.access_token,
			exp: Date.now() + json.expires_in * 1000,
		};
		return json.access_token;
	}
}

function b64url(b: Buffer | Uint8Array): string {
	return Buffer.from(b).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
}

// HMAC import kept for parity with older FCM legacy path; not currently used.
void createHmac;
