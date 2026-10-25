# CEONIX Ride

> ### Modified version notice (AGPLv3 §5a)
>
> **CEONIX Ride** is a **modified version** of
> [headunit-revived](https://github.com/andreknieriem/headunit-revived) by André Rinas,
> which is itself based on the original
> [headunit](https://github.com/mikereidis/headunit) by Michael A. Reid.
> It is **not** the official Headunit Revived app and is not affiliated with or endorsed
> by its authors.
>
> **First modified:** 21 July 2026
>
> **Changes made in this fork:**
> - Rebranded to "CEONIX Ride" (new name, new launcher icon, `applicationId` changed to
>   `com.ceonix.ride`, version reset to 1.0)
> - Wi-Fi Direct: fixed an `IllegalStateException` from `WifiP2pConfig.Builder` when a
>   band-only config is built on some Android 12+ / OEM frameworks
> - Wi-Fi Direct: added an idempotency guard so repeated Bluetooth `ACL_CONNECTED` events
>   no longer tear down and rebuild an in-progress P2P group
> - Increased the wireless reconnect grace window from 20 s to 60 s
> - Native AA: automatically re-poke the paired phone on device unlock
>   (`ACTION_USER_PRESENT`) when the session is not connected
> - Added a one-time battery-optimisation exemption request
> - Changed first-install/reset defaults: Native AA connection mode, auto-connect last
>   session, and auto-start on boot are enabled by default
>
> **Source code for this modified version:**
> <https://github.com/onurakturk/ceonix-ride> — each released version is tagged there.
> The AGPL requires that everyone who receives the app can also obtain this source; the same
> address is shown in the app under Settings › Info › Open-source licence.
>
> This program is free software: you can redistribute it and/or modify it under the terms
> of the **GNU Affero General Public License v3** (see [LICENSE](LICENSE)). It is
> distributed **without any warranty**; see the licence for details.
>
> **Safety:** this software can be distracting. Do not operate it while riding or driving.

---

## What is CEONIX Ride?

CEONIX Ride turns an Android device into a **wireless Android Auto head unit**, tailored for
motorcycle use. Alongside standard Android Auto projection it adds a purpose-built **Ride
dashboard** and a Material 3 home screen you can use as the device launcher.

## Features

- **Wireless Android Auto** (Native AA) — connects to the phone over Wi-Fi Direct, without a
  cable. On Android 10 and later the head unit cannot read its own Wi-Fi Direct address, so the
  small **CEONIX Ride Helper** app (`companion/`) fetches it from the phone once, during setup.
- **Ride dashboard** — live speed, a sensor-fused lean angle with persisted max left/right, and a
  third card you swipe between: longitudinal **g-force** (acceleration and braking, with recorded
  maxima) or a GPS-based **sunset countdown** with a headlights reminder. The card you pick is
  remembered across restarts, reboots and updates.
- **Launcher Mode** — use CEONIX Ride as the device home screen, with an APPS drawer for the
  installed apps.
- **Material 3 UI** — tonal cards, a dark/light theme toggle, and a status bar with clock,
  battery, GPS and Bluetooth indicators.
- **Auto-connect & auto-start** — reconnects on unlock and can start on boot.
- **19 languages**, application dark mode, and a separate Android Auto night mode.

## Build & install

Requirements: Android SDK (compileSdk 36), NDK 27, JDK 17.

```bash
./gradlew :app:assembleGithubDebug
adb install -r app/build/outputs/apk/github/debug/com.ceonix.ride_<version>_debug.apk
```

There are two flavours. `github` is the sideload build (`com.ceonix.ride`). `playstore` is the
Google Play build (`com.ceonix.ride.app`): it leaves out the dummy VPN service and three
permissions Play restricts or that nothing uses. The two have different package names, so they
install side by side, but only one can run at a time: both open the same Android Auto port.

```bash
./gradlew :app:bundlePlaystoreRelease :companion:bundleRelease
```

A release build is signed with your upload key when `secrets.properties` (git-ignored, in the
project root) provides it; without it the debug key is used so the build can still be tested.

```properties
HEADUNIT_KEYSTORE_FILE=your-upload-key.jks
HEADUNIT_KEY_ALIAS=your-alias
HEADUNIT_KEYSTORE_PASSWORD=...
HEADUNIT_KEY_PASSWORD=...
```

## License

CEONIX Ride is distributed under the **GNU Affero General Public License v3** — see
[LICENSE](LICENSE). Attribution for the upstream projects this work is based on is in the
notice at the top of this file.
