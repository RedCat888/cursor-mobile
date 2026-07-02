/**
 * Ensures the bundled Composer bridge extension is active (Glass mode).
 */
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { bridgePing } from "./composer-bridge.js";

const BUNDLED_BRIDGE = join(
	"/Applications/Cursor.app/Contents/Resources/app/extensions/cursor-browser-automation",
	"dist",
	"cursor-mobile-bridge.js",
);
const INSTALL_HINT =
	"Run: cd cursor-extension && bash install.sh — then restart Cursor (Cmd+Q). Bridge loads via cursor-browser-automation (Glass allowlist).";

function bridgeResponding(): Promise<boolean> {
	return bridgePing()
		.then((r) => r.ok === true && r.host === "extension")
		.catch(() => false);
}

/** Ensure the extension-owned bridge socket is accepting connections. */
export async function ensureBridgeHost(): Promise<void> {
	if (await bridgeResponding()) return;

	if (!existsSync(BUNDLED_BRIDGE)) {
		throw new Error(`Composer bridge is not installed. ${INSTALL_HINT}`);
	}

	throw new Error(
		`Composer bridge is installed but not running. Restart Cursor completely (Cmd+Q, reopen). ${INSTALL_HINT}`,
	);
}
