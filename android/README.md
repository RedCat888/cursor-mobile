# Cursor Mobile — Android app

A native Kotlin/Compose app for your S24 that talks to the [Mac daemon](../mac-daemon) via the [relay](../relay), end-to-end encrypted.

## Build prerequisites

Pick **one** path.

### Path A — Android Studio (easiest)

1. Install **Android Studio Koala (2024.1)** or newer.
2. `File → Open…` → select this `android/` folder. Studio will offer to download the right Android SDK, build tools, and JDK 17 automatically.
3. Plug your S24 in (Developer Mode + USB Debugging on), or use the Wireless Debugging pairing flow.
4. Hit **Run** ▶︎. Studio installs the debug APK.

### Path B — Command line

Install JDK 17 and the Android command line tools, then:

```bash
brew install --cask zulu@17
brew install gradle
sdkmanager "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle wrapper                # one-time: generates the wrapper jar
./gradlew :app:assembleDebug  # output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Signed release APK (for sideloading to your S24)

```bash
keytool -genkey -v -keystore cursormobile.keystore -alias cursormobile \
  -keyalg RSA -keysize 4096 -validity 10000

cat > keystore.properties <<EOF
storeFile=$(pwd)/cursormobile.keystore
storePassword=...
keyAlias=cursormobile
keyPassword=...
EOF

./gradlew :app:assembleRelease  # → app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

## First launch

1. Open the app. You'll see the **Pair** screen.
2. On your Mac, run `cursor-mobile-daemon pair` and scan the QR.
3. Done. The bottom nav exposes **Agents · Files · Terminal · MCP · Settings**.

The app starts a foreground service that maintains the WebSocket so streaming chunks and notifications keep flowing even when the app is backgrounded. The persistent notification doubles as a connection indicator.

## Push notifications (optional)

If you want the app to ring when an agent finishes while killed, drop a `google-services.json` into `app/`. The presence of the file activates the `com.google.gms.google-services` Gradle plugin automatically. See [`mac-daemon/README.md`](../mac-daemon/README.md) for the matching Firebase service account config.

Without `google-services.json` everything still works — you just only get notifications when the foreground service is alive.

## Security model

- An X25519 keypair is generated on first launch and stored in Android Keystore-backed `EncryptedSharedPreferences`.
- Pairing exchanges pubkeys via the relay; both sides derive a shared key with HKDF-SHA256.
- Every application body is encrypted with ChaCha20-Poly1305 (IETF, 12-byte random nonce). Relay sees ciphertext only.
- App requires biometric (or device PIN) unlock on launch when configured in Settings. Without unlock the WS still maintains, but the UI stays gated.

## Architecture

- **`data/net/RemoteClient`** — singleton WS connection w/ auto-reconnect, encryption, ACK/dedupe, persistent outbox.
- **`data/db/`** — Room: agents, messages, outbox.
- **`data/repo/`** — One repository per feature area; ViewModels use them.
- **`work/RelayForegroundService`** — keeps WS alive in the background (data-sync foreground type).
- **`push/FcmService`** — Firebase Cloud Messaging handler.
- **`ui/`** — Compose screens, Material 3, dark Cursor-like palette.
