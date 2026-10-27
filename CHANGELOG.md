# Changelog

CEONIX Headunit is a fork of [headunit-revived](https://github.com/andreknieriem/headunit-revived),
taken from its 3.1.1 release. This file lists only the changes made in CEONIX Headunit and
CEONIX Setup. For the history before the fork, see the upstream project.

## 1.3.0 · CEONIX Setup 3.0 (2026-10-27)

- The apps have new names: **CEONIX Headunit** (was CEONIX Ride) and **CEONIX Setup** (was
  CEONIX Ride Helper). "Ride" is now only the name of the riding dashboard.
- New package names: `com.ceonix.headunit` (Google Play), `com.ceonix.headunit.dev` (built from
  source) and `com.ceonix.setup`. They install next to the old apps, not over them; move your
  settings with Settings › Backup and remove the old app, since two head unit apps cannot run at
  the same time.
- The phone now sees the head unit as "CEONIX Headunit". If Android Auto does not reconnect,
  remove the old head unit from Android Auto's connected cars on the phone and connect again.
- CEONIX Setup: a step-by-step "How to set up" guide with pictures that covers the whole first
  setup (permissions, Bluetooth pairing, finding the head unit). It opens on first launch and from
  the top of the home screen.

## 1.2.32 (2026-10-26)

- New setting, **Stay connected** (Settings › General). When it is off, disconnecting Android Auto
  from the phone keeps it disconnected instead of reconnecting a few seconds later, and Exit on the
  head unit also ends the Wi-Fi Direct link, so the phone no longer shows Android Auto as
  available. Turn the screen off and on, or tap Android Auto on the home screen, to connect again.
  It is on by default, which keeps the previous behaviour.
- The Google Play build has its own package name.

## 1.2.29 (2026-10-13) · first public release

Everything below changed between the fork and the first public release.

### Connection

- Wireless Android Auto without a helper app on the phone is the default. The head unit connects
  on its own when the app starts, when the phone comes into Bluetooth range and when you unlock the
  screen.
- Android 10 and later do not let an app read its own Wi-Fi Direct address, which the phone needs
  to connect. A new **Wi-Fi Setup** screen (Settings › Connection) receives the address from the
  CEONIX Setup app on the phone; it can also be typed in by hand. You do this once.
- A warning appears when the phone was sent the network details but never joined.
- Fixed a crash creating the Wi-Fi Direct group on Android 12 and later.
- Fixed reconnecting after Exit, which could need an app restart.
- Fixed cases where the Bluetooth handshake stopped listening (Bluetooth slow to start at boot, or
  a socket error) and the phone was poked but never connected.
- The phone is poked once at boot instead of several times at once.
- Wi-Fi Direct peer discovery stops once the phone has joined. It kept the radio busy and caused
  stutter and freezes.
- First-time setup no longer rebuilds the Wi-Fi Direct group over and over while the address is
  missing.

### Picture and touch

- New **Interface size** setting (Small / Medium / Large) chooses the resolution and density for
  you. It replaces the manual resolution and DPI settings and the setup wizard.
- Correct aspect ratio and margins on wide screens; the picture is no longer stretched or
  zoomed.
- Fixed touch landing in the wrong place when a session started in the background.
- Fixed video freezing after a decoder restart (an H.264 stream was taken for HEVC).
- Recovery from short link drops: the head unit asks the phone for a fresh picture, shows
  "Reconnecting" after 10 seconds and restarts the session if the picture does not return. After a
  decoder restart it waits for a clean frame instead of showing a smeared picture.

### Android Auto on the head unit

- **Pause** (back button › Pause) returns to the CEONIX home screen while music, calls and
  navigation keep running; the Android Auto button brings the picture back.
- Android Auto no longer takes over the screen while you are on Ride, Apps or Settings. It connects
  in the background and you open it when you want.
- The Exit / Pause dialog has two large, glove-friendly buttons and fits short screens.

### Ride dashboard

- New **Ride** screen: speed from the head unit's GPS, and lean angle from its motion sensors, with
  the maximum left and right lean remembered (long-press resets).
- A third card you swipe between, and the choice is remembered:
  - **G-force**: acceleration and braking, with recorded maxima.
  - **Altitude and sunset**: altitude with a route profile, and the sunset time with a countdown
    and a headlights reminder after dark.
- Lean, g-force and altitude keep being recorded while Android Auto is on screen.
- The last GPS position is remembered, so sunset and altitude appear right away after a cold start.

### Home screen and look

- New Material 3 home screen with four cards: Android Auto, Ride, Apps and Settings.
- Status bar with clock, battery, GPS status (searching, locked, off), a light / dark / automatic
  theme button and a warning when Bluetooth is off.
- **Launcher mode**: use the app as the device's home screen, with a drawer for other apps.
- New logo, app icon (including Android 13 themed icon), splash screen and colour palette.

### Settings

- Settings is reorganised into categories: General, Display, Audio, Connection, Backup, Advanced
  and Info. Low-level connection options moved under Advanced.
- Changes are saved immediately; there is no Save button.
- A permission screen explains each permission and shows what is missing.
- Dark mode settings were simplified. Night mode follows your real location, or fixed hours if no
  location is known yet.
- The open-source licence is shown in Settings › Info.
- 20 languages.
- The Google Play build drops the VPN service and permissions the app does not use.
