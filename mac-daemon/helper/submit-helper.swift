import AppKit
import ApplicationServices
import CoreGraphics
import Foundation

let home = FileManager.default.homeDirectoryForCurrentUser.path
let mobileDir = "\(home)/.cursor-mobile"
let submitAppPath = "\(home)/Applications/Cursor Mobile Submit.app"

private let kVK_Return: CGKeyCode = 0x24
private let kVK_Escape: CGKeyCode = 0x35
private let minComposerFieldScore = 55

func writeResult(_ path: String, _ payload: [String: Any]) {
	guard let data = try? JSONSerialization.data(withJSONObject: payload),
	      let text = String(data: data, encoding: .utf8)
	else { return }
	try? text.write(toFile: path, atomically: true, encoding: .utf8)
}

func permissionHelp() -> String {
	"macOS blocked keyboard injection. Enable **Cursor Mobile Submit** in " +
		"System Settings → Privacy & Security → Accessibility " +
		"(app: \(submitAppPath)). Then retry."
}

func hasAccessibility() -> Bool {
	AXIsProcessTrusted()
}

func requireAccessibility() throws {
	if hasAccessibility() { return }
	throw NSError(
		domain: "CursorMobileSubmit",
		code: 1002,
		userInfo: [NSLocalizedDescriptionKey: permissionHelp()]
	)
}

func bootstrapApp() {
	let app = NSApplication.shared
	app.setActivationPolicy(.prohibited)
}

func cursorPid() -> pid_t? {
	for app in NSWorkspace.shared.runningApplications {
		if app.localizedName == "Cursor" { return app.processIdentifier }
		if app.bundleURL?.lastPathComponent == "Cursor.app" { return app.processIdentifier }
	}
	return nil
}

func postKey(keyCode: CGKeyCode, flags: CGEventFlags = []) throws {
	let source = CGEventSource(stateID: .combinedSessionState)
	guard let keyDown = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true),
	      let keyUp = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false)
	else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1002,
			userInfo: [NSLocalizedDescriptionKey: "failed to create key event"]
		)
	}
	keyDown.flags = flags
	keyUp.flags = flags
	keyDown.post(tap: .cghidEventTap)
	keyUp.post(tap: .cghidEventTap)
}

func axRole(_ element: AXUIElement) -> String? {
	var value: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXRoleAttribute as CFString, &value) == .success else { return nil }
	return value as? String
}

func axAttr(_ element: AXUIElement, _ attr: String) -> String? {
	var value: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, attr as CFString, &value) == .success else { return nil }
	if let s = value as? String { return s }
	if let n = value as? NSNumber { return n.stringValue }
	return nil
}

func axFocused(_ element: AXUIElement) -> Bool {
	var value: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXFocusedAttribute as CFString, &value) == .success else { return false }
	return (value as? Bool) == true
}

func axSetValue(_ element: AXUIElement, _ text: String) -> Bool {
	let cf = text as CFString
	return AXUIElementSetAttributeValue(element, kAXValueAttribute as CFString, cf) == .success
}

func fieldMetaBlob(_ element: AXUIElement) -> String {
	let parts = [
		axAttr(element, kAXRoleAttribute as String),
		axAttr(element, kAXDescriptionAttribute as String),
		axAttr(element, kAXTitleAttribute as String),
		axAttr(element, "AXPlaceholderValue"),
		axAttr(element, kAXHelpAttribute as String),
		axAttr(element, kAXSubroleAttribute as String),
	].compactMap { $0 }
	return parts.joined(separator: " ").lowercased()
}

func scoreTextField(_ element: AXUIElement, composerName: String?) -> Int {
	let role = axRole(element) ?? ""
	guard role == "AXTextArea" || role == "AXTextField" else { return Int.min / 2 }

	let blob = fieldMetaBlob(element)

	if blob.contains("terminal") { return Int.min / 2 }
	if blob.contains("debug console") { return Int.min / 2 }
	if blob.contains("output") && !blob.contains("tool") { return -800 }
	if blob.contains("problems") { return -800 }
	if blob.contains("search") && !blob.contains("agent") && !blob.contains("composer") { return -300 }
	if blob.contains("filter") && !blob.contains("agent") { return -250 }

	var score = 5
	if role == "AXTextArea" { score += 20 }

	if blob.contains("composer") { score += 160 }
	if blob.contains("chat") { score += 130 }
	if blob.contains("agent") { score += 110 }
	if blob.contains("ask") || blob.contains("plan") { score += 90 }
	if blob.contains("message") { score += 75 }
	if blob.contains("prompt") { score += 60 }
	if blob.contains("follow") { score += 40 }

	if let name = composerName?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !name.isEmpty {
		if blob.contains(name) { score += 220 }
		let prefix = name.split(separator: " ").prefix(3).joined(separator: " ")
		if prefix.count > 4 && blob.contains(prefix) { score += 140 }
	}

	if axFocused(element) { score += 45 }

	return score
}

func axCollectScoredFields(
	_ element: AXUIElement,
	depth: Int,
	composerName: String?,
	into: inout [(AXUIElement, Int)]
) {
	if depth > 24 { return }

	let role = axRole(element) ?? ""
	if role == "AXTextArea" || role == "AXTextField" {
		let score = scoreTextField(element, composerName: composerName)
		if score >= minComposerFieldScore {
			into.append((element, score))
		}
	}

	var childrenValue: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &childrenValue) == .success,
	      let children = childrenValue as? [AXUIElement]
	else { return }

	for child in children {
		axCollectScoredFields(child, depth: depth + 1, composerName: composerName, into: &into)
	}
}

func bestComposerField(in appElement: AXUIElement, composerName: String?) -> AXUIElement? {
	var fields: [(AXUIElement, Int)] = []
	axCollectScoredFields(appElement, depth: 0, composerName: composerName, into: &fields)
	return fields.max(by: { $0.1 < $1.1 })?.0
}

func axFindAndPress(_ element: AXUIElement, matching name: String, depth: Int = 0) -> Bool {
	if depth > 16 { return false }
	let needle = name.lowercased()

	for attr in [kAXTitleAttribute as String, kAXDescriptionAttribute as String, kAXHelpAttribute as String] {
		if let text = axAttr(element, attr)?.lowercased(), text.contains(needle) {
			if AXUIElementPerformAction(element, kAXPressAction as CFString) == .success {
				return true
			}
		}
	}

	var childrenValue: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &childrenValue) == .success,
	      let children = childrenValue as? [AXUIElement]
	else { return false }

	for child in children {
		if axFindAndPress(child, matching: name, depth: depth + 1) { return true }
	}
	return false
}

func focusComposerWorkspace(composerName: String?, composerId: String?) {
	guard let pid = cursorPid() else { return }
	let appElement = AXUIElementCreateApplication(pid)

	if let composerName, !composerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
		if axFindAndPress(appElement, matching: composerName.trimmingCharacters(in: .whitespacesAndNewlines)) {
			return
		}
	}

	if let composerId, composerId.count >= 8 {
		_ = axFindAndPress(appElement, matching: String(composerId.prefix(8)))
	}
}

func axFindSubmitButton(_ element: AXUIElement, depth: Int = 0) -> AXUIElement? {
	if depth > 18 { return nil }
	let role = axRole(element) ?? ""
	let title = (axAttr(element, kAXTitleAttribute as String) ?? "") +
		(axAttr(element, kAXDescriptionAttribute as String) ?? "")
	if role == "AXButton" &&
		(title.localizedCaseInsensitiveContains("send") ||
		 title.localizedCaseInsensitiveContains("submit") ||
		 title.localizedCaseInsensitiveContains("run"))
	{
		return element
	}

	var childrenValue: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &childrenValue) == .success,
	      let children = childrenValue as? [AXUIElement]
	else { return nil }

	for child in children {
		if let found = axFindSubmitButton(child, depth: depth + 1) { return found }
	}
	return nil
}

func axSubmitToComposer(text: String, composerName: String?, composerId: String?) throws {
	guard let pid = cursorPid() else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1003,
			userInfo: [NSLocalizedDescriptionKey: "Cursor is not running"]
		)
	}

	let appElement = AXUIElementCreateApplication(pid)
	focusComposerWorkspace(composerName: composerName, composerId: composerId)
	Thread.sleep(forTimeInterval: 0.18)

	guard let field = bestComposerField(in: appElement, composerName: composerName) else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1004,
			userInfo: [
				NSLocalizedDescriptionKey:
					"Could not find the Composer input in Cursor. Open the target chat in Agents, then retry.",
			]
		)
	}

	_ = AXUIElementPerformAction(field, kAXRaiseAction as CFString)
	guard axSetValue(field, text) else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1005,
			userInfo: [NSLocalizedDescriptionKey: "Could not write into the Composer input field"]
		)
	}
	Thread.sleep(forTimeInterval: 0.08)

	if let submit = axFindSubmitButton(appElement) {
		if AXUIElementPerformAction(submit, kAXPressAction as CFString) == .success { return }
	}

	_ = AXUIElementSetAttributeValue(field, kAXFocusedAttribute as CFString, kCFBooleanTrue)
	try postKey(keyCode: kVK_Return)
}

func submitMessage(text: String, composerName: String?, composerId: String?) throws {
	let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
	guard !trimmed.isEmpty else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1,
			userInfo: [NSLocalizedDescriptionKey: "empty message"]
		)
	}

	try requireAccessibility()
	try axSubmitToComposer(text: trimmed, composerName: composerName, composerId: composerId)
}

func showAlert(title: String, message: String, buttons: [String] = ["OK"]) -> NSApplication.ModalResponse {
	let alert = NSAlert()
	alert.messageText = title
	alert.informativeText = message
	for (index, title) in buttons.enumerated() {
		alert.addButton(withTitle: title)
		if index == 0 { alert.buttons[index].keyEquivalent = "\r" }
	}
	return alert.runModal()
}

func openAccessibilitySettings() {
	let urls = [
		"x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Accessibility",
		"x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
	]
	for urlString in urls {
		if let url = URL(string: urlString), NSWorkspace.shared.open(url) { return }
	}
}

func runGrantPermissionsFlow() {
	let app = NSApplication.shared
	app.setActivationPolicy(.regular)
	app.activate(ignoringOtherApps: true)

	_ = showAlert(
		title: "Cursor Mobile Submit — Accessibility",
		message:
			"Click Continue — macOS will prompt you to allow Accessibility " +
			"for **Cursor Mobile Submit** (not the Setup app).",
		buttons: ["Continue", "Cancel"]
	)

	let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
	let trusted = AXIsProcessTrustedWithOptions(opts)

	if trusted {
		_ = showAlert(
			title: "Ready",
			message: "✓ Accessibility is enabled.\n\nSend from your phone — messages go to Composer, not Terminal."
		)
		exit(0)
	}

	_ = showAlert(
		title: "Enable Accessibility",
		message: "Turn ON **Cursor Mobile Submit** in Accessibility.\n\n\(submitAppPath)",
		buttons: ["Open Settings", "Close"]
	)
	openAccessibilitySettings()
	exit(1)
}

func runPingOnce(resultPath: String) {
	bootstrapApp()
	writeResult(resultPath, ["ok": true, "accessibility": hasAccessibility()])
	exit(0)
}

func runSubmitOnce(reqPath: String) {
	let resultPath = reqPath + ".result"
	bootstrapApp()

	guard let data = try? Data(contentsOf: URL(fileURLWithPath: reqPath)),
	      let req = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
	else {
		writeResult(resultPath, ["ok": false, "error": "invalid request file"])
		exit(1)
	}

	let text = req["text"] as? String ?? ""
	let composerName = req["composerName"] as? String
	let composerId = req["composerId"] as? String

	do {
		try submitMessage(text: text, composerName: composerName, composerId: composerId)
		writeResult(resultPath, ["ok": true])
		exit(0)
	} catch let err as NSError {
		let msg = err.localizedDescription.lowercased()
		if msg.contains("1002") || msg.contains("assistive") || msg.contains("accessibility") {
			writeResult(resultPath, ["ok": false, "error": permissionHelp()])
		} else {
			writeResult(resultPath, ["ok": false, "error": err.localizedDescription])
		}
		exit(1)
	} catch {
		writeResult(resultPath, ["ok": false, "error": String(describing: error)])
		exit(1)
	}
}

func axFindStopButton(_ element: AXUIElement, depth: Int = 0) -> AXUIElement? {
	if depth > 18 { return nil }
	let role = axRole(element) ?? ""
	let title = (axAttr(element, kAXTitleAttribute as String) ?? "") +
		(axAttr(element, kAXDescriptionAttribute as String) ?? "")
	if role == "AXButton" &&
		(title.localizedCaseInsensitiveContains("stop") ||
		 title.localizedCaseInsensitiveContains("cancel") ||
		 title.localizedCaseInsensitiveContains("abort"))
	{
		return element
	}

	var childrenValue: CFTypeRef?
	guard AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &childrenValue) == .success,
	      let children = childrenValue as? [AXUIElement]
	else { return nil }

	for child in children {
		if let found = axFindStopButton(child, depth: depth + 1) { return found }
	}
	return nil
}

func cancelGeneration() throws {
	try requireAccessibility()
	guard let pid = cursorPid() else {
		throw NSError(
			domain: "CursorMobileSubmit",
			code: 1003,
			userInfo: [NSLocalizedDescriptionKey: "Cursor is not running"]
		)
	}
	let appElement = AXUIElementCreateApplication(pid)
	if let stop = axFindStopButton(appElement) {
		if AXUIElementPerformAction(stop, kAXPressAction as CFString) == .success { return }
	}
	try postKey(keyCode: kVK_Escape)
}

func runCancelOnce(resultPath: String) {
	bootstrapApp()
	do {
		try cancelGeneration()
		writeResult(resultPath, ["ok": true])
		exit(0)
	} catch let err as NSError {
		writeResult(resultPath, ["ok": false, "error": err.localizedDescription])
		exit(1)
	} catch {
		writeResult(resultPath, ["ok": false, "error": String(describing: error)])
		exit(1)
	}
}

let args = CommandLine.arguments
if args.contains("--grant-permissions") {
	runGrantPermissionsFlow()
} else if let idx = args.firstIndex(of: "--ping-once"), idx + 1 < args.count {
	runPingOnce(resultPath: args[idx + 1])
} else if let idx = args.firstIndex(of: "--submit-once"), idx + 1 < args.count {
	runSubmitOnce(reqPath: args[idx + 1])
} else if let idx = args.firstIndex(of: "--cancel-once"), idx + 1 < args.count {
	runCancelOnce(resultPath: args[idx + 1])
} else {
	fputs(
		"cursor-mobile-submit: use --grant-permissions, --ping-once <out>, --submit-once <req.json>, or --cancel-once <out>\n",
		stderr,
	)
	exit(2)
}
