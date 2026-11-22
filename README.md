# Venuebox for Android TV

Turns an Android TV / Google TV (a TV or a streaming stick) into a screen for a Bar Games
box. Paired once, it shows the game TV pages the box sends, rendered on the TV itself: no
encoding on the box and no delay.

## Install

Download `venuebox-<version>.apk` from [Releases](../../releases) and sideload it:

1. On the TV: Settings → Device Preferences → About → press Build 7 times, then
   Developer options → turn on USB / network debugging.
2. From a computer on the same network: `adb connect <tv address>` then
   `adb install -r venuebox-<version>.apk`.

## Set up

1. Open **Venuebox**. It looks for the box on the network; if it finds exactly one it uses it.
   Otherwise press **BACK** (or MENU/OK) for settings: choose a box, or type its address
   (e.g. `192.168.1.20`; port 7210 is the default).
2. The TV shows a 6-digit code. On the box: **TVs page → Venuebox apps** → type the code.
3. Done: the TV now appears on the box's TVs page like any other display.

To come back by itself after a power cut, the app needs **Display over other apps**. The settings
panel shows "Start at boot: needs 'Display over other apps' → Grant" until it has it.
If the TV has no settings page for that permission, grant it from a computer:

```
adb shell appops set com.bargames.venuebox SYSTEM_ALERT_WINDOW allow
```

## Videos

With box 0.28 or later, apps such as Movies can play a video on this screen: it plays full
screen over the game page and goes back to the page when it ends. The app tells the box which
formats this device plays smoothly (including 4K and HDR where both the TV and the device
support them), so the media server only converts what's needed. On the remote: **OK** or
play/pause pauses and resumes, **left** jumps back 10 s, **right** forward 30 s, **Back** stops,
**down** opens **Audio & subtitles** (box 0.29+), and next/previous ask the app for the next or
previous video. The box's TVs page also has a remote for the screen; its arrows, OK and Back work
in the player and on game pages, and its volume keys where the device controls its own volume.

## Tablets and phones

The same APK runs on Android tablets and phones (Android 8+), always in landscape. Open the
settings panel with the back gesture or button.

- **Start at boot** works as on a TV (grant "Display over other apps"), but Android waits for the
  first unlock: set the screen lock to None or Swipe. Some brands also block apps at boot until
  you allow "auto-start" or turn off battery optimization for Venuebox.
- **Home-screen mode**, for a tablet that only shows games: settings panel → Home screen →
  **Turn on**, then pick Venuebox as the home app (Always). It then comes back after every reboot
  and on the home button, with no other permission. **Turn off** gives the normal home screen
  back. Not offered on TVs: Google TV doesn't allow another home app.
- **Touch mode**, for tablets and touch TVs that players use directly: settings panel → Touch mode
  → **Turn on**. Pages can't be zoomed, taps can't leave the box's pages, and games treat the
  screen as shared (they forget the last person). Typing uses Android's keyboard; if this
  device's own keyboard is awkward to tap (e.g. a TV box with a touch panel), choose **Use the
  big on-screen keyboard** to get the games' own big keys instead. Both are off by default.

## Build

JDK 17 and the Android SDK (platform 35, build-tools 35.0.0):

```
gradlew assembleDebug testDebugUnitTest
```

Protocol: [NATIVE-CLIENTS.md](https://github.com/thezigpc/venueplay/blob/main/docs/NATIVE-CLIENTS.md).
