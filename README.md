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
  → **Turn on**. Games show their own big on-screen keyboard instead of Android's, pages can't
  be zoomed, and taps can't leave the box's pages. Off by default.

## Build

JDK 17 and the Android SDK (platform 35, build-tools 35.0.0):

```
gradlew assembleDebug testDebugUnitTest
```

Protocol: [NATIVE-CLIENTS.md](https://github.com/thezigpc/venueplay/blob/main/docs/NATIVE-CLIENTS.md).
