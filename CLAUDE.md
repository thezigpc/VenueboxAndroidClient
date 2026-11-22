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
- TV pages are laid out at **1920x1080 CSS px** and scaled to the panel, like every other client
  (a document-start script pins the viewport meta to width=1920 at the panel's scale; a 4K TV
  renders 1920 CSS px at device scale 2). Without it a 1080p xhdpi TV would give 960x540.
  The scale comes from the WebView's real size and is refit live when it changes.
- Highest fidelity: on start the app asks for the TV's sharpest display mode (`bestMode`: most
  pixels, same shape, never a lower refresh rate). Pages stay 1920 CSS px, scaled to fit; they
  get 4K pixels only where the device draws apps at 4K (many sticks draw apps at 1080p and send
  4K to the TV). `hello`'s screen `w`/`h` is the panel's real size (the display mode), and a
  mode change is sent as `screens`. The emulator has one mode, so test this on a real 4K TV.
- A full-screen WebView for `show`: JavaScript and DOM storage on, media autoplay without a
  gesture, screen kept on, no pointer, not focusable (the D-pad stays with the app).
- `identify` flashes the screen's name. `state` messages on every change. `preview` answers
  with a JPEG of the window (PixelCopy, so video/WebGL are included), base64, at most ~200 KB.
- Video player (box 0.28.0/0.28.1, `VideoPlayer`): `hello.video` declares what it plays, built
  once from MediaCodecList (`videoCaps`/`pictureRanges` in `client/VideoFormats.kt`):
  - h264 always; hevc/av1/vp9 only with a hardware decoder.
  - maxHeight 2160 when the display has a 4K mode and a hardware decoder does 4K, else 1080.
  - passthrough from Media3's AudioCapabilities.
  - ranges: the display's HDR types × HEVC Main10 (hdr10/hdr10plus/hlg) or a Dolby Vision decoder.

  `video` ops (play/pause/resume/stop/status/seek):
  - ExoPlayer plays full screen over the WebView, which keeps running.
  - Every op gets a `video-reply`; `video-state` goes out on its own changes, at most every 10 s while playing.
  - End, stop, Back, a new `show`/`off`, or leaving the app close the player (`stopped`).
  - Remote: OK/play-pause, left -10 s, right +30 s, Back stops. Previews copy the video's surface.

  Tracks (box 0.29.0, `client/VideoTracks.kt`): `hello.video.tracks = true`; play's `tracks`.
  - A track's `index` picks the n-th audio / text group (the stream's own text groups only).
  - `url` subtitles are added as SubtitleConfigurations with Format id `vb-sub:<id>`.
  - Selections wait for `onTracksChanged`. `audio`/`subtitle` ids go in status replies and video-state.
  - Down (or Captions) opens the "Audio & subtitles" menu.
  - A track not in the stream, or media Next/Previous, sends `video-remote`.

  Remote keys (`hello.keys`, `Controller.remoteKeys`): arrows, Select, Back, plus volume when
  `AudioManager.isVolumeFixed` is false. `key` drives the player while a video plays,
  otherwise the WebView (Select = Enter, Back = Escape).

  **The link and poster carry the media server's key:** never log, store or report them.
  Media3's logging is off, there's no EventLogger, `BoxMsg.Video.toString` hides them, and
  errors are described from codes, never exception text. Test on the emulator with
  `--es debugVideo play --es url <url>` (debug builds; see `debugShow`).
- The token is AES-GCM encrypted with a key in the Android Keystore (`KeystoreTokenStore`).
- Start on boot (BOOT_COMPLETED) and go straight to the last box, so a venue TV comes back
  after a power cut. Android 10+ only lets the receiver open the screen if the app may
  "display over other apps": grant it in the app's settings panel, or
  `adb shell appops set com.bargames.venuebox SYSTEM_ALERT_WINDOW allow`.
- Locked to landscape (`sensorLandscape`). Tablets/phones get an opt-in home-screen mode
  (`HomeMode`): the manifest's `HomeAlias` (category HOME) is disabled until staff turn it on in
  the settings panel, then Android's home-app setting opens. Hidden on TVs.
- Touch mode (settings panel, off by default; tablets and touch TVs as kiosks): the WebView
  takes taps and focus, taps can only navigate to the box's host or the shown page's host,
  Enter belongs to the page, and `window.__BARGAMES_SHARED_SCREEN__ = true` is injected at
  document start (GameKit 0.10.2: games forget the last person; onPageStarted fallback).
  Typing uses Android's keyboard.
  - "Use the big on-screen keyboard" (touch-mode option, off by default; for devices whose
    own keyboard is poor, like a TV box with a touch panel): also injects
    `window.__BARGAMES_KEYBOARD__ = true` (GameKit's `<OnScreenKeyboard />`), and focusing an
    `inputmode="none"` field hides Android's keyboard (`VenueboxTouch.hideKeyboard()`, a JS
    bridge that exists only in this case).
  - Touch mode off: nothing is injected. Pop-ups (window.open, target=_blank) are always
    dropped; zoom is always off. Test a page's keyboard with `?bg_keyboard=1`.
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
