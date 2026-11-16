# VenueboxAndroidClient

An Android TV / Google TV app (phones and tablets later) that pairs with a Bar Games box
("Venuebox") and renders the game TV pages itself in a WebView: no encoding on the box,
no delay.

## The contract (don't invent protocol)

The box defines the protocol; this app follows it. To change it, ask the GameBroker
session (the platform) to change the box first.

- https://github.com/thezigpc/venueplay/blob/main/docs/NATIVE-CLIENTS.md
- Field names (source of truth): https://github.com/thezigpc/venueplay/blob/main/apps/broker/src/venuebox/protocol.ts
- A working client to copy behaviour from: https://github.com/thezigpc/VenueboxDesktopClient
  (Electron: discovery, pairing, hello/welcome, show/off/reload/identify, state, previews, reconnect).

In short: the box is at `http://<box LAN IP>:7210`.
- Discovery: mDNS `_venuebox._tcp` (TXT `v`, `id`, `name`, `path`) via NsdManager. Remember the
  box by its TXT `id`, not its IP.
- Pairing: `POST /pair/start` gives a 6-digit code shown on screen; poll `GET /pair/status?id=`
  for the token (handed out once; the next poll is 404).
- Control: WebSocket `/client`. First message `hello { v: 1, token, platform, model, appVersion,
  screens: [one], deliveries: ["native-url"] }`. The box answers `welcome`, or `error` + close
  (4401 bad token, 4403 revoked, 4400 protocol, 4409 replaced). The box pings every 30 s;
  reconnect with backoff.
- `idle` is not a message: an idle screen arrives as an ordinary `show`. `settings` (zoom,
  overscan, rotation) is not built on the box yet: unknown messages are ignored.

## Project notes

- Kotlin, one Activity, plain Views (no Compose), built for TV: Leanback launcher intent,
  banner, D-pad navigable. BACK / MENU / OK open the settings panel; BACK closes it.
- A full-screen WebView for `show`: JavaScript and DOM storage on, media autoplay without a
  gesture, screen kept on, no pointer, not focusable (the D-pad stays with the app).
- `identify` flashes the screen's name. `state` messages on every change. `preview` answers
  with a JPEG of the window (PixelCopy, so video/WebGL are included), base64, at most ~200 KB.
- The token is AES-GCM encrypted with a key in the Android Keystore (`KeystoreTokenStore`).
- Start on boot (BOOT_COMPLETED) and go straight to the last box, so a venue TV comes back
  after a power cut. Android 10+ only lets the receiver open the screen if the app may
  "display over other apps": grant it in the app's settings panel, or
  `adb shell appops set com.bargames.venuebox SYSTEM_ALERT_WINDOW allow`.
- Address fallback: the settings panel always offers "type the box address" (host or
  host:port). The emulator sits behind NAT and can't see mDNS; some venue networks block it.
- Sideloaded APK, no Play Store. Signed with the debug key for now: an APK built on another
  machine has a different key, so installing it over this one needs an uninstall first.
- Don't send power, input or app-launch commands to real TVs or sticks without asking the user.

## Layout

- `app/src/main/java/com/bargames/venuebox/protocol/`: message shapes (`Protocol.kt`) and box
  addresses (`BoxAddress.kt`). Plain Kotlin + org.json.
- `.../client/BoxClient.kt`: pairing, the control channel, reconnects, relocation. No Android
  imports, so it's unit-tested on the JVM against a fake box.
- `.../android/`: `Controller` (app-wide connection and settings), `MainActivity` (WebView,
  status page, settings panel, identify, previews), `Discovery` (NsdManager), `Store`
  (config + Keystore token), `BootReceiver`.
- `app/src/test/`: `ProtocolTest`, `BoxClientTest` + `FakeBox` (MockWebServer speaking the
  contract: pairing, welcome, close codes, reconnects).

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35.0.0); `local.properties` points
at the SDK (`sdk.dir=...`, git-ignored).

```
gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Public repo rules

Never commit IP addresses, Tailscale addresses, usernames, passwords, tokens or device IDs.
Test addresses go in `local.properties` or a `*.local.md` file (both git-ignored).
