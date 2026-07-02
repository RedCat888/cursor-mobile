#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CURSOR_APP="/Applications/Cursor.app"
HOST_EXT="$CURSOR_APP/Contents/Resources/app/extensions/cursor-browser-automation"
MAIN="$HOST_EXT/dist/extension.js"
ORIG="$HOST_EXT/dist/extension.original.js"
BRIDGE="$HOST_EXT/dist/cursor-mobile-bridge.js"
WORKBENCH="$CURSOR_APP/Contents/Resources/app/out/vs/workbench/workbench.desktop.main.js"
WORKBENCH_ORIG="$CURSOR_APP/Contents/Resources/app/out/vs/workbench/workbench.desktop.main.original.js"
PATCH_ANCHOR='__decorate([Xl(o4f)],B8S.prototype,"run",null),jt(B8S);'

echo ">> building cursor-mobile-bridge extension…"
cd "$ROOT"
npm install --no-audit --no-fund 2>/dev/null || true
npm run build

if [[ ! -d "$CURSOR_APP" ]]; then
	echo "ERROR: Cursor.app not found at $CURSOR_APP"
	exit 1
fi

if [[ ! -f "$MAIN" ]]; then
	echo "ERROR: cursor-browser-automation not found at $HOST_EXT"
	exit 1
fi

echo ">> patching workbench with cursorMobileBridge.submit command…"
if [[ ! -f "$WORKBENCH" ]]; then
	echo "ERROR: workbench.desktop.main.js not found"
	exit 1
fi

if ! grep -q "cursorMobileBridge.submit" "$WORKBENCH" 2>/dev/null; then
	if [[ ! -f "$WORKBENCH_ORIG" ]]; then
		echo "   backing up original workbench.desktop.main.js"
		cp "$WORKBENCH" "$WORKBENCH_ORIG"
	fi
	SNIPPET="$(tr -d '\n' < "$ROOT/workbench-patch.snippet")"
	node -e "
const fs = require('fs');
const file = process.argv[1];
const anchor = process.argv[2];
const snippet = process.argv[3];
let s = fs.readFileSync(file, 'utf8');
if (!s.includes(anchor)) {
  console.error('ERROR: workbench patch anchor not found (Cursor update?)');
  process.exit(1);
}
if (s.includes('cursorMobileBridge.submit')) process.exit(0);
fs.writeFileSync(file, s.replace(anchor, anchor + snippet));
" "$WORKBENCH" "$PATCH_ANCHOR" "$SNIPPET"
	echo "   workbench submit command installed"
else
	echo "   workbench submit command already present"
fi

echo ">> installing bridge into cursor-browser-automation (Glass allowlist extension)…"

if ! grep -q "cursor-mobile-bridge-bootstrap" "$MAIN" 2>/dev/null; then
	echo "   backing up original extension.js"
	cp "$MAIN" "$ORIG"
fi

cp "$ROOT/dist/extension.js" "$BRIDGE"
cp "$ROOT/bootstrap.js" "$MAIN"

echo ""
echo "✓ Installed bridge into: $HOST_EXT"
echo "✓ Workbench command: cursorMobileBridge.submit"
echo ""
echo "Restart Cursor completely (Cmd+Q, reopen)."
echo "Then verify:"
echo "  echo '{\"op\":\"ping\"}' | nc -U ~/.cursor-mobile/composer-bridge.sock"
echo "  ( echo '{\"op\":\"submit\",\"composerId\":\"YOUR_ID\",\"text\":\"test\"}'; sleep 8 ) | nc -U ~/.cursor-mobile/composer-bridge.sock"
echo ""
echo "Submit runs in workbench via insertIntoChat — programmatic, no paste/automation."
