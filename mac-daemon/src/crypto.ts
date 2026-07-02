/**
 * Pairing crypto.
 *
 * Both sides:
 *   1. Generate a long-lived X25519 keypair the first time pairing happens.
 *   2. Exchange pubkeys via the relay (relay sees pubkeys, never derives keys).
 *   3. Derive a shared symmetric key via X25519 + HKDF-SHA256, info = "cursor-mobile/v1".
 *   4. Encrypt every application body with XChaCha20-Poly1305, AAD = envelope.id|seq.
 *
 * Control frames (HELLO, ACK, PING, PAIR.*) are cleartext.
 */
import { chacha20poly1305 } from "@noble/ciphers/chacha";
import { randomBytes } from "@noble/ciphers/webcrypto";
import { x25519 } from "@noble/curves/ed25519";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha2";
import { Buffer } from "node:buffer";

export interface KeyPair {
	pubKey: string;
	privKey: string;
}

export function generateKeyPair(): KeyPair {
	const priv = x25519.utils.randomPrivateKey();
	const pub = x25519.getPublicKey(priv);
	return {
		pubKey: Buffer.from(pub).toString("base64"),
		privKey: Buffer.from(priv).toString("base64"),
	};
}

export function deriveSharedKey(ourPrivB64: string, peerPubB64: string): Uint8Array {
	const ourPriv = Buffer.from(ourPrivB64, "base64");
	const peerPub = Buffer.from(peerPubB64, "base64");
	const shared = x25519.getSharedSecret(ourPriv, peerPub);
	return hkdf(sha256, shared, new Uint8Array(0), "cursor-mobile/v1", 32);
}

export interface SealedBody {
	n: string; // base64 nonce
	c: string; // base64 ciphertext (includes auth tag)
}

export function seal(key: Uint8Array, plaintext: unknown, aad: string): SealedBody {
	const nonce = randomBytes(12);
	const cipher = chacha20poly1305(key, nonce, Buffer.from(aad, "utf8"));
	const data = Buffer.from(JSON.stringify(plaintext ?? null), "utf8");
	const ct = cipher.encrypt(data);
	return {
		n: Buffer.from(nonce).toString("base64"),
		c: Buffer.from(ct).toString("base64"),
	};
}

export function open(key: Uint8Array, sealed: SealedBody, aad: string): unknown {
	const nonce = Buffer.from(sealed.n, "base64");
	const ct = Buffer.from(sealed.c, "base64");
	const cipher = chacha20poly1305(key, nonce, Buffer.from(aad, "utf8"));
	const pt = cipher.decrypt(ct);
	const text = Buffer.from(pt).toString("utf8");
	return JSON.parse(text);
}

export function pubKeyFingerprint(pubB64: string): string {
	const digest = sha256(Buffer.from(pubB64, "base64"));
	return Buffer.from(digest).subarray(0, 8).toString("hex");
}
