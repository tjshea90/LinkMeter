# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. Update the Status
section and run `tools/checkpoint.sh "what changed"` after every meaningful step,
because a session can be cut off at any time.

## What it is

One small Android app (package `com.tj.linkmeter`) installed on two devices.

- **Phone** (Samsung Galaxy A15 5G, Android 16, acts as the Wi-Fi hotspot): runs a
  tiny foreground service that answers status requests with JSON.
- **Tablet** (MediaTek MT8168 Go, Android 10, ARMv7, no Play services): joins the
  phone's hotspot, asks the phone for its status, and shows three meters: phone
  battery, phone cell signal, and the tablet's own Wi-Fi strength to the phone.

## Status

Done:
- [x] Project, manifest, icon, Gradle wrapper (8.14.3), AGP 8.7.3, signing key in repo
- [x] Phone sender service, tablet viewer, meter card view
- [x] GitHub Actions workflow that builds the release APK and publishes it to the
      `latest` release
- [x] Local syntax check of all Java files (javac parse only)

Not done / unknown:
- [ ] NEVER COMPILED. No Android SDK or network in the authoring session. First real
      build happens in GitHub Actions. If it fails, fix the compile errors first.
- [ ] No GitHub repo attached yet at time of writing. Needs one (see Resume).
- [ ] Not tested on either device.
- [ ] Phone sender behaviour with the screen off for long periods is unverified.

## How to resume

1. Read this file and CHECKPOINTS.md (newest entries at the bottom).
2. Make sure the project is in a GitHub repo you can push to. If not, ask the user to
   create an empty repo and attach it.
3. Build: push to `main`. The workflow in `.github/workflows/build.yml` builds
   `assembleRelease` and posts `LinkMeter.apk` to the release named `latest`.
   Locally (needs the Android SDK): `./gradlew assembleRelease`.
4. Fix whatever the first build reports, then checkpoint.

## Design decisions (and why)

- **No libraries at all** (no AndroidX, no Kotlin): the tablet is a low-RAM Go device
  (`ro.config.low_ram=true`, memory class 128 MB). The APK stays tiny and starts fast.
- **Pull, not push**: the tablet asks, the phone answers. The phone has no timers and
  does nothing between requests, so it can idle.
- **Tablet polls only while the screen is open.** `onPause` shuts the worker thread
  down completely. No background service on the tablet.
- **Poll interval**: 5 s default; Eco 15 s; Live 2 s. After 3 failed polls it backs off
  to at least 15 s.
- **Phone service**: no wake lock, no Wi-Fi lock, `START_NOT_STICKY`, stops itself after
  2 h with no tablet request. Foreground type is `specialUse` because `dataSync`
  services are time-limited on Android 15+.
- **Pairing**: the phone makes a 4-digit code; the tablet sends it with every request.
  Wrong code gets HTTP 403. The status contains nothing sensitive.
- **Raw sockets, not HttpURLConnection**: avoids the cleartext-HTTP restrictions.
- **targetSdk 34**: avoids forced edge-to-edge layout. minSdk 29 (tablet is API 29).
- **Release build is signed with a fixed key in `app/debug.keystore`** (password
  `android`) so each build installs as an update. Sideload use only.
- **Phone address**: the tablet uses the Wi-Fi gateway address from `DhcpInfo` (that is
  the phone while on its hotspot). There is a manual override in Settings.

## Tablet hardware facts (from the user's own device report)

- Model alps 711 / tb8168p1_bsp, Android 10 (API 29), patch 2021-07-05
- Low-RAM Go device, memory class 128 MB
- Screen 600x973 at 170 dpi, system font scale 1.2: layouts use dp/sp and measure text
  height, so large fonts don't clip
- Settings app exposes the standard intents; `ACTION_APPLICATION_DETAILS_SETTINGS` and
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` are used
- The user's launcher log showed `socket failed: EPERM` on local TCP connects even though
  INTERNET was listed as granted. Cause unknown (possibly the process started before the
  grant). The tablet app detects EPERM and shows a clear on-screen message.

## Known risks / ideas

- Cell level comes from `SignalStrength.getCellSignalStrengths()`. Network label is
  `getDataNetworkType()`; "5G (NSA)" is a guess when an NR strength is present but the
  type says LTE. Unverified.
- If the phone's Samsung battery manager kills the sender, set the app to Unrestricted
  battery use (button in the phone screen).
- Newer Android versions may add a permission for local-network access. If connections
  start failing after an OS update, check that first.
- Possible features: low-battery alert on the tablet, hotspot client count.
