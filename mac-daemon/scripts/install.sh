#!/usr/bin/env bash
# Installs the daemon as a launchd LaunchAgent so it auto-starts at login
# and gets restarted by macOS if it crashes.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -d node_modules ]; then
  echo ">> installing npm deps…"
  npm install --no-audit --no-fund
fi

echo ">> building…"
npm run build

echo ">> installing submit helper (Accessibility app)…"
bash helper/install-helper.sh

echo ">> optional: install bundled bridge into Cursor.app (Glass allowlist via cursor-browser-automation)…"
if bash ../cursor-extension/install.sh 2>/dev/null; then
  echo "   bundled bridge installed — restart Cursor (Cmd+Q) if it is open"
else
  echo "   skipped bundled install"
fi

NODE_BIN="$(command -v node)"
DAEMON_JS="$(pwd)/dist/cli.js"
HOME_DIR="$HOME"
mkdir -p "$HOME_DIR/.cursor-mobile"
PLIST_DEST="$HOME_DIR/Library/LaunchAgents/com.cursormobile.daemon.plist"

sed \
  -e "s|__NODE_PATH__|$NODE_BIN|g" \
  -e "s|__DAEMON_PATH__|$DAEMON_JS|g" \
  -e "s|__HOME__|$HOME_DIR|g" \
  scripts/com.cursormobile.daemon.plist > "$PLIST_DEST"

launchctl unload "$PLIST_DEST" 2>/dev/null || true
launchctl load "$PLIST_DEST"
launchctl start com.cursormobile.daemon

echo
echo "✓ Installed. Logs: $HOME_DIR/.cursor-mobile/daemon.{out,err}.log"
echo "  Pair with: $(pwd)/node_modules/.bin/tsx src/cli.ts pair"
echo "  Stop:      launchctl unload $PLIST_DEST"
