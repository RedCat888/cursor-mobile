// Smoke test: simulate a Mac + Phone completing a pair and exchanging a frame.
import { WebSocket } from "ws";

const RELAY = process.env.RELAY ?? "http://localhost:9301";
const macPub = "MAC_PUB_KEY_B64";
const phonePub = "PHONE_PUB_KEY_B64";

const startRes = await fetch(`${RELAY}/pair/start`, { method: "POST" });
const { pairId, code } = await startRes.json();
console.log("pair started:", { pairId, code });

const wsUrl = (role) =>
	RELAY.replace("http", "ws") + `/pair/${pairId}/ws?role=${role}`;

function openSide(role, pubKey) {
	const ws = new WebSocket(wsUrl(role));
	ws.on("open", () => {
		ws.send(JSON.stringify({
			v: 1, id: `${role}-hello`, seq: 0, type: "HELLO", ts: Date.now(),
			body: { role, pairId, pubKey, deviceLabel: role },
		}));
		ws.send(JSON.stringify({
			v: 1, id: `${role}-claim`, seq: 0, type: "PAIR.CLAIM", ts: Date.now(),
			body: { code, pubKey, deviceLabel: role },
		}));
	});
	ws.on("message", (data) => {
		const env = JSON.parse(data.toString());
		console.log(`[${role}] <-`, env.type, JSON.stringify(env.body)?.slice(0, 80) ?? "");
		if (env.type === "PAIR.OK") {
			// Send one application frame from this side to the other.
			ws.send(JSON.stringify({
				v: 1, id: `${role}-test`, seq: 1, type: "AGENT.LIST", ts: Date.now(),
				body: { ciphertext: `hello from ${role}` },
			}));
		}
	});
	return ws;
}

const mac = openSide("mac", macPub);
const phone = openSide("phone", phonePub);

setTimeout(() => {
	mac.close();
	phone.close();
	console.log("done");
	process.exit(0);
}, 3000);
