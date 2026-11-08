# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. A session can be cut off at any time, so after every
meaningful step run `tools/checkpoint.sh "what changed"` (commit + push, retries the push, warns when this file is stale)
and keep the **RESUME HERE** block below true. Write the explanation here BEFORE the big commit, not after: the last
session died between its push and its write-up, and the next one had to rebuild the story from git and from the dead
session's transcript.

## RESUME HERE (newest session first)

**2026-11-08, session "Faster remote control update"**: MERGED into `main` (the user said "merge it"), so `latest` is the
build of that merge. The code compiles green in CI. **Nothing in it has run on a device yet.**

1. What happened. The previous session (`session_01UWi8ZxcnuAwP2jZg1uh8Gh`) built the faster remote control (commit
   `14b3e12`, published as `latest`) and died of "Prompt is too long" (970K tokens in a 500K window) before it wrote any
   of it down or told the user. Nothing was lost: `git log`, `CHECKPOINTS.md` and the section "Remote screen control"
   below are the record.
2. Bug found by reading the code (CI cannot catch this kind): `Api.handle` never routed `/screen/video` or `/screen/ctl`,
   so in `14b3e12` both answered 404 and the tablet silently used Pictures and one-process-per-tap input. The live video and
   the live touch never ran. Fixed in `Api.java`; the fix is in the current `latest`.
3. Hardening: screenrecord is stopped when the tablet leaves (it only writes when the picture changes, so it used to keep
   running on a still screen); a touch connection that drops mid-gesture lifts the finger; the touch helper only lives while
   the Screen tab is open; picture capture no longer blocks touch; if video cannot start, the tablet shows screenrecord's own
   error text; the status line shows fps and kbit/s; the touch channel has a keepalive and reconnects on the next
   finger-down; SPS/PPS that arrive alone are merged into the next picture; a stalled decoder restarts the stream instead of
   skipping frames.
4. NEXT, in order:
   a. The user installs the new `latest` APK on the tablet, then Control > "Update phone from this tablet" (the version code
      comes from the CI run number, so the new build is newer than the 0.2.25 on the devices), and field-tests with the
      checklist in "Remote screen control", reporting the status line text.
   b. Fix what the test shows. The likely trouble spots are listed there, each with a remedy.
   c. The user has not answered the open question about the two dead network-adb buttons (open item 5).
5. Branch rule for cloud sessions: develop and push only on the designated `ccr-*` branch, and merge into `main` only when the
   user says so (a fast-forward push of the branch to `main` publishes `latest`; the head commit of that push must not contain
   `[skip ci]`). Start every session with `git fetch --all && git branch -r && git log --oneline main..origin/<newest ccr-*
   branch>` so work that sits on an unmerged branch is not mistaken for missing.

## What it is

One Android app (`com.tj.linkmeter`, pure Java, no UI libraries) installed on two devices.
The user's phone is the hotspot; the tablet joins it and controls the phone.

- **Phone**: Samsung Galaxy A15 5G, Android 16, not rooted, Total Wireless (Verizon). Runs a
  small always-on foreground service (starts at boot) that answers the tablet over the hotspot.
  Privileged things go through **Shizuku** (the user already runs it for his BandLock app).
- **Tablet**: MediaTek MT8168 Go, Android 10 (API 29), ARMv7, 128 MB heap, 600x973 @170 dpi,
  font scale 1.2, no Play services. Tabs: Home, Speed, Bands, Control, Files, Settings.
  Only the tab on screen does work, and only while the app is in front.

Repo: github.com/tjshea90/LinkMeter. Every push to `main` builds the release APK and publishes
it as the `latest` release (`LinkMeter.apk`).

## State (what is done and what is not)

All code compiles and builds green in CI. "Where things stand" says what the user has actually confirmed on the
devices; anything it does not list as confirmed is "written and built, not field-tested". The Confidence column is the
older estimate, kept for the unconfirmed rows.

| Feature | Where | Needs | Confidence |
|---|---|---|---|
| Battery, cell signal, tablet Wi-Fi meters | HomeTab, Status | none | high |
| One-time pairing (no numbers) | Api.pair, Cfg tokens | tap "Pair tablet" on phone once | high |
| Always-on phone service, boot start | SenderService, BootReceiver | none | medium (Samsung may kill it: set battery Unrestricted) |
| Speed test (phone's mobile data, gauge GUI) | SpeedTest, SpeedTab, SpeedView | none | medium (uses speed.cloudflare.com endpoints) |
| File manager both ways | Files, FilesTab | phone: All files access; tablet: storage permission | medium |
| Airplane on/off, hotspot off, power off, screen off | Actions | Shizuku | medium |
| Battery saver profile (auto when tablet connects) | Eco | Shizuku | medium |
| Force 4G / allow 5G | Actions.setNr | Shizuku | LOW until probed (two routes, see below) |
| Cell survey | Cells.survey (parses `dumpsys telephony.registry`) | Shizuku | LOW until probed (text format unverified) |
| Band lock | Cells.applyBands, ShellMain | Shizuku + modem cooperation | EXPERIMENTAL |
| Screen tab, Pictures mode (raw screencap to JPEG) and slow taps | Screen.frame, ScreenTab | Shizuku | medium |
| Screen tab, live H.264 video + live touch + full screen + quality | Screen.video/ctl, VideoStream, CtlChannel, ShellMain inject | Shizuku (screenrecord, app_process helper) | LOW until field-tested |

## Where things stand (latest, replaces the old "open issue" note)

Working on the user's devices: pairing, Home meters, Speed test (download ok; upload is limited by the user's weak
carrier uplink, not the test), Files, Restore everything (verified), ShellMain telephony helper (Read lock info shows
real values), band lock (the modem ACCEPTED a lock request), Probe, Prepare phone, and (confirmed 2026-11-08) updating
the phone from the tablet: "Update phone from this tablet" worked, both devices were on 0.2.25 and the phone said "Success".
Built, not yet confirmed on the devices: hotspot band chooser + restart, battery saver profile, Force 4G / Allow 5G via
the API route, Guard auto-undo, signal fallback through Shizuku (the "no signal" meter on Home), and the whole Screen
tab (first version: pictures + slow taps; second version: live video + live touch, see its own section below).

Dead end, confirmed on the user's phone: "Start Shizuku from the tablet" over network adb. The phone refused with
`Failed to set property 'persist.adb.tcp.port'`, so LinkMeter cannot turn network adb on, and `adb tcpip 5555` from a computer
only lasts until the next reboot. The two buttons (Control tab: "Set up network adb" and "Start Shizuku", with AdbClient
behind them) still exist and do nothing useful on this phone. The previous session offered: (1) keep the tablet-hotspot
trick (join the phone to the tablet's hotspot once so it reconnects by itself after a restart), (2) start Shizuku over a
USB cable with the tablet as host (needs an OTG cable and tablet host mode, untested), (3) remove the two dead buttons.
The user has not answered.

Open items, in order:
1. Field test of the live remote control (this session's work), see "Remote screen control".
2. Bands tab was rebuilt: grid of LTE (29) and NR (27) band buttons, a status card with Keep / Clear and a countdown,
   "Now using" from a scan 6 s after each lock. Needs a field test: which bands the modem accepts, which gives the best
   upload (the user's goal is better indoor upload; the phone sat on LTE band 66 with sinr about 4; try 13, 5, 2, 4).
3. Speed test servers: Cloudflare first, then Hetzner (ash-speed) and OVH as fallbacks after HTTP 429 and timeouts
   hit the user's connection. The two fallbacks are unverified. Upload exists on Cloudflare only.
4. Ask the user what his BandLock app already does to change bands.
5. Ask the user which option they want for the dead network-adb buttons (see "Dead end" above).
6. Keep HANDOFF.md current; run `tools/checkpoint.sh` after each change.

## Probe results from the user's phone (first real data)

Paired and working over the hotspot. Shizuku runs fine on the hotspot once started from a computer over USB
with Wireless debugging OFF (wireless-debugging start dies when Wi-Fi turns off for the hotspot).
- Phone is SM-S156V (Verizon A15 5G), Android 16 (SDK 36), board mt6835 (MediaTek), "Samsung RIL v5.0".
- `cmd phone` has `get-allowed-network-types-for-users` and `set-allowed-network-types-for-users` (help gives no
  usage), plus `radio`, `restart-modem`, `cc` (carrier config). `get` prints WORDS, not a number: `LTE|LTE_CA`.
  So the phone is currently LTE-only (5G already off). Actions.parseTypes/typesText now read and write the words;
  the set command's argument form is unknown, so setNr tries the words, then the number, each with and without
  `-s 0`, and checks by reading back. The probe now also prints the set command's own usage.
- `cmd connectivity airplane-mode` works (printed `disabled`). `cmd wifi help` lists no start/stop-softap.
- `dumpsys telephony.registry`: serving cell is LTE band 5 (`mBands=[5]`), rsrp about -104, level 2,
  `isUsingCarrierAggregation=true`, ServiceState `mChannelNumber=2560` (EARFCN, band 5). Cell lists HIDE
  `mEarfcn` and `mPci` as `[****]`, so Cells.survey takes the serving channel from `mChannelNumber`.
- Force 4G first try: route A (ShellMain) failed and route B's `set-allowed-network-types-for-users` rejected
  both the word form and the decimal mask ("No valid NETWORK_TYPES_BITMASK"); its real format is still unknown.
  Before the failure the phone allowed GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA plus NR.
- `Bands > Read lock info` (ShellMain info) showed the TelephonyManager methods exist (setAllowedNetworkTypesForReason(int,long),
  setSystemSelectionChannels(List,Executor,Consumer), ...) but every call threw a NullPointerException in
  `TelephonyServiceManager.getTelephonyServiceRegisterer()`: the app_process helper never ran the mainline
  service-wrapper setup. Fixed by calling `ActivityThread.initializeMainlineModules()` (fallback:
  TelephonyFrameworkInitializer.setTelephonyServiceManager) at the start of ShellMain.telephony(). NOT yet confirmed on the phone.
- Cell scan on the phone showed the serving cell as LTE B66 (ch 66611), not B5 (the user wants B5 indoors).
- Not yet checked on the phone: ShellMain route (`Bands > Read lock info`), band lock result, Prepare phone,
  Force 4G / Allow 5G outcome, battery saver, speed test, Files.

## Updating the phone from the tablet (new)

Control tab > "Update phone from this tablet": the tablet uploads its own installed APK (`sourceDir`) to the phone
(`POST /apk`, Updater.receive). The phone checks it is this package and not older (versionCode), then runs
`cp` + `pm install -r` through Shizuku, detached, with the result written to
`Android/data/com.tj.linkmeter/files/install.log` (shown on the Phone tab as "Last update from the tablet").
The install kills the app; BootReceiver restarts the phone service after MY_PACKAGE_REPLACED. Only the same
signing key can update, and the package name is checked, so it cannot install other apps. versionCode now comes
from GITHUB_RUN_NUMBER (versionName 0.2.N), so every CI build is newer. First install of this version on both
devices is manual; after that only the tablet needs a manual install. NOT yet tested on the devices.

## Remote screen control (Screen tab): design, protocol, risks, field test

The user's request (2026-11-08): the screen share was "very laggy"; wanted it faster, a full-screen control mode, and
quality options. Design copied from scrcpy: live hardware video plus a persistent touch channel, instead of one screenshot
and one `input` process per event. Everything needs Shizuku on the phone and the setting "Let the tablet see and control
this screen" (on by default). Nothing runs unless the tablet's Screen tab is open.

Phone side (`Screen.java`, routed in `Api.handle`; every route needs the paired token):
- `GET /screen/video?w=<px>&br=<bit/s>`: a long reply. Headers `X-Size: <phoneW>x<phoneH>` and `X-Video: <w>x<h>` (both video
  sides are multiples of 16). Body = packets `[int32 big-endian length][length bytes of Annex-B H.264]`. A negative length means
  "the next -length bytes are a UTF-8 message" (why video could not start; it ends the stream). Length 0 is ignored.
  Source: `exec screenrecord --output-format=h264 --size WxH --bit-rate N - 2>/data/local/tmp/linkmeter-screenrecord.err`
  started through `Priv.spawn`. screenrecord stops after 3 minutes, so it is restarted (the stream then carries new SPS/PPS
  and a keyframe). Packets are cut by timing: when no more bytes are readable 3 ms after a read, what has been read is sent
  as one packet (a guess that one encoder write is one frame). A watcher thread ends screenrecord when the tablet closes the
  connection. Two quick failures in a row send the screenrecord error text and end the stream.
- `GET /screen/ctl`: a long connection. The tablet sends lines `d x y`, `m x y`, `u x y` (finger down, move, up, in the
  phone's pixels), `k code` (only the keys in `Screen.KEYS`), `p` (keepalive, ignored). The phone feeds them to
  `ShellMain inject`: a helper started with `app_process` as the shell user that calls `InputManager.injectInputEvent` by
  reflection (a few ms per event instead of a new `input` process, a few hundred ms). The helper is started with `exec` and
  lives only while a touch connection is open. If a connection ends while a finger is down, the phone lifts the finger.
- `GET /screen/frame?w=&q=`: Pictures mode. `screencap` raw (16-byte header: width, height, format, dataspace, then RGBA) is
  scaled and sent as JPEG; it falls back to `screencap -p` into a file. One-shot actions `/screen/tap|swipe|key|on|off|text|pin|state`
  are the slow path used when the touch channel is not up.

Tablet side (`ScreenTab`, `VideoStream`, `CtlChannel`, `AspectSurfaceView`, `PhoneScreenView`):
- Video mode: a MediaCodec H.264 decoder renders straight onto a SurfaceView and every decoded buffer is released at once
  (no queue). SPS/PPS that arrive alone are held and sent in front of the next picture. If the decoder stops taking input the
  stream is ended and restarted (clean keyframe). Chips: Video / Pictures and Fast / Balanced / Sharp (video 540 / 720 / 1080
  px wide at 1.5 / 3 / 8 Mbit/s; pictures 360 / 540 / 720 px at JPEG quality 40 / 55 / 70). "Full" gives full screen with a
  small bar (Exit, Back, Home, Recents, Wake, Sleep, Unlock); the tablet's Back key leaves full screen.
- If video shows no picture within 8 s, or the phone refuses, the tab switches itself to Pictures for that visit and says why
  (the saved choice stays Video). While live the status line reads `Live video, <quality> . N fps . N kbit/s`.
- Touch: a gesture is sent over the live channel if it was up when the finger went down; otherwise one finished tap or swipe
  is sent when the finger lifts, and the channel is retried on the next finger-down (not more often than every 5 s).

Field test (the user does this with both devices in hand; this sandbox cannot):
1. Put the same build on both devices (tablet: Control > Update phone from this tablet). Screen tab, Video.
2. Expect a picture in about 2 s and a status line with fps. Report the exact status text, how it feels when tapping, and
   anything black or garbled. If it fell back to Pictures, copy the reason (it includes screenrecord's own error if it ran).
3. Try Fast / Balanced / Sharp, Full, tap, long-press, scroll, Back/Home/Recents, Wake/Sleep, then leave the tab.
4. After leaving the tab, check on the phone (Shizuku shell or Probe) that nothing lingers:
   `ps -A | grep -E "screenrecord|app_process"` should show no screenrecord and no `ShellMain inject`.

Likely trouble spots, with the remedy to try:
- The picture stays a few frames behind, or the last change never appears when the screen goes still: the MTK decoder is
  holding frames (screenrecord only writes when the picture changes). Try vendor low-latency keys on the decoder format
  (the tablet is API 29, so `KEY_LOW_LATENCY` does not exist; look for the MediaTek key), or ask the phone for a repeat
  frame. Pictures mode is the safe fallback meanwhile.
- Smeared or garbled picture after fast motion: a frame was split by the 3 ms timing guess. Fix: let the tablet cut the
  stream into pictures by parsing NAL units (a slice with `first_mb_in_slice == 0` starts a new picture) instead.
- A hiccup every 3 minutes: the screenrecord restart. Expected; could be hidden by starting the next one early.
- Touch lands a few pixels off: screenrecord letterboxes when the phone's shape is not an exact multiple of 16; map touches
  through the letterbox if it matters.
- The phone is rotated while streaming: the stream keeps the size it started with, so the picture and the touch mapping go
  wrong until the tab is left and re-entered. Fix if it matters: restart the stream when the phone's size changes.
- screenrecord refuses on this Samsung build (the status line will say what it printed): the last resort is a scrcpy-style
  server in ShellMain (virtual display + MediaCodec through hidden APIs), a bigger piece of work.

## Safety: getting the phone back if a change breaks mobile data

The user asked for this explicitly (a band lock must never leave the phone unrecoverable). Layers:
1. `Actions.restoreAll` = "Restore everything": clears the band lock, puts network types back to what they were
   before LinkMeter first changed them (Saved.java; else the usual default 840583 = 2G/3G/4G + 5G), restores the
   hotspot band, battery saver, turns airplane mode off and mobile data on. Every step is independent. Buttons: phone
   Phone tab (top, works with no tablet and no internet), tablet Control tab and Bands tab, and a "Restore" action on
   the phone's LinkMeter notification. "Restart modem" (`cmd phone restart-modem`) is the stronger second step.
2. Band lock auto-reverts after 2 minutes unless the tablet taps "Keep the lock" (Cells.armRevert).
3. `Guard` watches mobile data for 10 minutes after any band lock or 4G/5G change; if the active network is not
   validated for 2 minutes in a row it runs `Actions.restoreRadio` and posts a notification.
4. Without Shizuku (e.g. after a reboot) the dialogs and the Phone tab show the manual steps (`Actions.MANUAL`) and a
   button opens the phone's mobile network settings. Network types persist across reboot; a band lock probably does not.
NOT yet tested on the phone. First real test should be done with the phone in hand.

## Working in this sandbox (what is blocked)

- No Android SDK: only GitHub Actions compiles. `tools/ci.sh "msg"` commits, pushes, waits and prints errors.
- `gh run list|view --repo tjshea90/LinkMeter` works (REST); GraphQL commands (`gh release view`) do not;
  use `gh api repos/tjshea90/LinkMeter/releases/tags/latest`.
- Hooks block curl/wget, `git config` of the identity, and `rm -rf`. WebFetch cannot open GitHub pages or
  AOSP source. Do not try to route around these.
- Commits end with the Co-Authored-By and Claude-Session trailer lines given by the session reminder.
- `.simple-workflow/` (plugin notes) is gitignored on purpose.
- Cloud sessions are bound to one `ccr-*` branch and must not push elsewhere. CI (`build-apk`) compile-checks pushes to
  `main` and `ccr-*`; only `main` publishes `latest`. A `ccr-*` run still attaches the APK as a run artifact.
- Check CI with the GitHub MCP tools (`actions_list` with `workflow_runs_filter.branch`, `get_job_logs` with `failed_only`);
  `tools/ci.sh` does the same with `gh`, which worked read-only here but is not the sanctioned route.
- `javac` exists but there is no Android jar. `cd app/src/main/java && javac -proc:none -d <scratch> <files>` still finds
  syntax errors and misspelled app-internal names: ignore errors whose `symbol:` is an Android, org.json or Shizuku class.
- CI compiles but cannot see a route, a permission or a lock that is simply missing. Trace each new feature end to end
  (tablet request -> `Api.handle` -> handler -> reply -> tablet parse) before calling it done.
- Keep one session's context small: a session that read and wrote about 970K tokens died of "Prompt is too long".
  Checkpoint, update this file, and continue in a fresh session instead of pushing on.

## Protocol (plain sockets, HTTP/1.0 style, port 8765)

Header `X-Token: <32 hex>`. The tablet's token is its own secret; the phone keeps the secrets of
paired tablets. Phone endpoints: `/s` (status), `/pair` (POST, only while the 2-minute Pair window
is open), `/act?name=` (POST), `/probe`, `/speed/{start,stop,status}`, `/cells`, `/band?set=`,
`/band/keep`, `/bandinfo`, `/apk` (POST, tablet-driven update), and the screen routes
`/screen/{state,frame,video,ctl,tap,swipe,key,on,off,text,pin}` (see "Remote screen control").
Every route except `/pair` is checked against the paired token in `Api.handle` before the router's `switch`, and a route that
`Screen.serve` (or any handler) knows but the `switch` does not list answers 404: add new routes in both places.

Actions (`/act?name=`): `eco_on`, `eco_off`, `screen_off`, `setup`, `net_get`, `net_lte`, `net_5g`
(answer immediately), and `airplane_on`, `hotspot_off`, `poweroff` (answer first, run 0.8 s later
because they cut the link). `airplane_off` also exists.

## The privileged layer (read before touching Priv/Actions/ShellMain)

1. `Priv.run(cmd)` runs a shell command via Shizuku (`Shizuku.newProcess` by reflection, because
   it is private in the API). Output ends with a `[rc=N]` marker that Priv strips.
2. `ShellMain` is started as the shell user with `app_process` (`Cells.shellMain`). It builds a
   fake context for package `com.android.shell` and calls the telephony system APIs by reflection:
   `getAllowedNetworkTypesForReason`, `setAllowedNetworkTypesForReason`,
   `setSystemSelectionChannels` (band lock). It is kept by R8 (see proguard-rules.pro).
3. 4G toggle tries route A (ShellMain `nt-get`/`nt-set`) then route B
   (`cmd phone get/set-allowed-network-types-for-users`). It reads the current bitmask, changes
   only the 5G bit (1<<19) and verifies by reading back. It refuses to write if the read looks wrong.
4. Band lock auto-reverts after 2 minutes unless the tablet taps "Keep the lock".

## First things to do on real devices (in this order)

1. Phone: install, choose Phone, Start, then in Shizuku allow LinkMeter. Tablet: install, choose
   Tablet; on the phone tap Pair tablet (2 min), open the tablet app. Home should show the meters.
2. Tablet > Control > **Prepare phone** (grants all-files access, battery exemption, phone permission).
3. Tablet > Control > **Probe phone**, copy the result. It lists `cmd phone help`, the network-type
   commands, the telephony.registry cell lines and baseband properties. Use it to finish whatever
   did not work: the 4G toggle, the cell survey parser, band lock.
4. Tablet > Bands > **Read lock info** prints which `setSystemSelectionChannels` / allowed-network
   methods this phone's telephony class really has (ShellMain `info`).
5. Check: Speed test, Files both ways, Force 4G then Allow 5G, Battery saver on/off (settings must
   return to their originals), Hotspot off.

## Design decisions

- No UI libraries. Only dependencies: Shizuku api and provider (provider pulls AndroidX).
- Pull model: the tablet asks, the phone answers. The phone has no timers; its accept thread sleeps.
  Tablet polls every 5 s (2/5/15 s in Settings), only while Home is showing, backs off to 15 s
  when the phone is unreachable.
- Eco profile only turns off background Wi-Fi/BLE scanning, Always-on display, Bluetooth, shortens
  screen timeout and makes sure tether offload is enabled. It never touches mobile data, radio, or
  CPU speed. Every value is saved first and restored (also 10 min after the tablet disappears).
- Raw sockets avoid cleartext-traffic rules. targetSdk 34 (no forced edge-to-edge), minSdk 29.
- Release APK is signed with the fixed key in `app/debug.keystore` (password `android`) so builds
  install as updates.
- Android 10 tablet storage: `requestLegacyExternalStorage` + READ/WRITE_EXTERNAL_STORAGE. Android 16
  phone: MANAGE_EXTERNAL_STORAGE ("All files access").

## Known risks and unknowns

- Shizuku stops when the phone reboots; it must be restarted (adb or wireless debugging). Wireless
  debugging needs a Wi-Fi network, and the user has none at home, so expect the adb-from-computer route.
- `Shizuku.newProcess` is found by name and 3 parameters. If a Shizuku update changes that, every
  privileged action reports "Shizuku could not start the command".
- Hotspot off is done by turning airplane mode on for 3 seconds then off (no direct command known).
  Check that the hotspot really stays off on this phone.
- Cell survey parses the text of `dumpsys telephony.registry`. If it shows "No cells were listed",
  the raw text is returned in `raw`; adjust the regexes in Cells.parse.
- A tablet whose app lost INTERNET at the socket level (EPERM seen in the user's launcher log) shows
  a clear message. Cause unknown.
- Some tablets or phones may block `adb`-less app_process use; ShellMain errors are shown as text.

## Files

app/src/main/java/com/tj/linkmeter: Cfg (prefs, trust), Http/Client (sockets), Api (router), SenderService (service),
BootReceiver, Status, Priv/Actions/Eco/Cells/ShellMain/Saved/Guard/Notice (privileged and safety), SpeedTest, Files, Net,
Call (tablet helpers), Updater (tablet-driven phone update), AdbClient (network adb, dead end), Ui, MeterView, SpeedView, Tab,
HomeTab/SpeedTab/BandsTab/ControlTab/FilesTab/SettingsTab/PhoneTab/ScreenTab, MainActivity.
Remote screen: Screen (phone), ScreenTab, VideoStream, CtlChannel, AspectSurfaceView, PhoneScreenView (tablet), ShellMain `inject`.
Tools: `tools/checkpoint.sh` (log, commit, push with retries), `tools/ci.sh` (same, then watches the build with gh),
`tools/handoff-check.sh` (warns when this file is stale). `CHECKPOINTS.md` is the append-only log they write.
