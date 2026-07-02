#!/usr/bin/env bash
# Builds Cursor Mobile Submit.app (on-demand via `open`, not launchd).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER_SRC="$ROOT/helper"
APP="$HOME/Applications/Cursor Mobile Submit.app"
SETUP_APP="$HOME/Applications/Cursor Mobile Submit Setup.app"
LEGACY_APP="$HOME/.cursor-mobile/CursorMobileSubmit.app"
LEGACY_PLIST="$HOME/Library/LaunchAgents/com.cursormobile.submit-helper.plist"
CONTENTS="$APP/Contents"
MACOS="$CONTENTS/MacOS"

mkdir -p "$MACOS" "$HOME/Applications"
cp "$HELPER_SRC/Info.plist" "$CONTENTS/Info.plist"

echo ">> compiling native submit helper…"
swiftc -O \
  -framework AppKit -framework ApplicationServices -framework CoreGraphics -framework Foundation \
  -o "$MACOS/cursor-mobile-submit" \
  "$HELPER_SRC/submit-helper.swift"

chmod +x "$MACOS/cursor-mobile-submit"
codesign -s - --force --deep "$APP" 2>/dev/null || true

echo ">> installing setup app…"
SETUP_MACOS="$SETUP_APP/Contents/MacOS"
rm -rf "$SETUP_APP"
mkdir -p "$SETUP_MACOS"
cp "$HELPER_SRC/Setup-Info.plist" "$SETUP_APP/Contents/Info.plist"
cat > "$SETUP_MACOS/cursor-mobile-setup" <<EOF
#!/bin/bash
set -euo pipefail
APP="$APP"
exec "\$APP/Contents/MacOS/cursor-mobile-submit" --grant-permissions
EOF
chmod +x "$SETUP_MACOS/cursor-mobile-setup"
codesign -s - --force --deep "$SETUP_APP" 2>/dev/null || true

rm -rf "$LEGACY_APP"
rm -f "$HOME/Applications/Cursor Mobile Submit Setup.command"

# Background launchd helper cannot inherit Accessibility — remove it.
if [ -f "$LEGACY_PLIST" ]; then
  launchctl unload "$LEGACY_PLIST" 2>/dev/null || true
  rm -f "$LEGACY_PLIST"
fi
pkill -f "CursorMobileSubmit.app/Contents/MacOS/cursor-mobile-submit" 2>/dev/null || true
pkill -f "Cursor Mobile Submit.app/Contents/MacOS/cursor-mobile-submit" 2>/dev/null || true
rm -f "$HOME/.cursor-mobile/submit-helper.sock" "$HOME/.cursor-mobile/submit-helper-status.json"

echo ">> testing accessibility via on-demand app launch…"
PING_OUT="$HOME/.cursor-mobile/submit-ping-test.result"
rm -f "$PING_OUT"
open -W -n "$APP" --args --ping-once "$PING_OUT" 2>/dev/null || true
if [ -f "$PING_OUT" ]; then
  echo "  ping result: $(cat "$PING_OUT")"
  rm -f "$PING_OUT"
else
  echo "  ⚠ ping test produced no result"
fi

echo
echo "IMPORTANT — after every install/update:"
echo "  1. System Settings → Accessibility → REMOVE **Cursor Mobile Submit**"
echo "  2. Click + and re-add: $APP"
echo "  3. You do NOT need Setup.app in Accessibility (remove it if listed)"
echo "  4. Verify:"
echo "       open -W -n \"$APP\" --args --ping-once /tmp/cm-ping.result && cat /tmp/cm-ping.result"
echo "     Expected: {\"ok\":true,\"accessibility\":true}"
echo
open "$HOME/Applications" 2>/dev/null || true
