/**
 * Submit via Cursor extension bridge — ComposerService.insertIntoChat (programmatic).
 */
import { bridgePing, bridgeSubmit } from "./composer-bridge.js";

export async function extensionBridgeAvailable(): Promise<boolean> {
	try {
		const resp = await bridgePing();
		return resp.ok === true && !!resp.version && /^0\.2\./.test(resp.version);
	} catch {
		return false;
	}
}

export async function extensionComposerSubmit(composerId: string, text: string): Promise<void> {
	if (!(await extensionBridgeAvailable())) {
		throw new Error(
			"Composer bridge is not running. Run: cd cursor-extension && bash install.sh — then restart Cursor (Cmd+Q).",
		);
	}
	await bridgeSubmit(composerId, text);
}

export async function extensionComposerCancel(composerId: string): Promise<void> {
	if (!(await extensionBridgeAvailable())) {
		throw new Error("Composer bridge is not running.");
	}
	const { bridgeCancel } = await import("./composer-bridge.js");
	await bridgeCancel(composerId);
}
