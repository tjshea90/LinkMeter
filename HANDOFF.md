# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. After every meaningful
step run `tools/ci.sh "what changed"`: it logs a checkpoint, commits, pushes, waits for the
GitHub build and prints the compile errors if it fails. A session can be cut off at any time.

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

All code compiles and builds green in CI. **Nothing has been run on either device yet.**
Treat everything below as "written and built, not field-tested".

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

## Open issue right now (read this before anything else)

First field test: the user installed the APK on both devices; the phone toast said "open LinkMeter on
the tablet" but pairing never happened, and the tablet's Home said "Tablet isn't on Wi-Fi" while it was.
Cause (probable): HomeTab gated all networking on `WifiInfo.getNetworkId() != -1`, which this tablet
(Android 10 MediaTek ROM) apparently does not report. Fix pushed (build 42433328029, green): `Net.phoneHost`
now takes the default route of the Wi-Fi network (ConnectivityManager/LinkProperties), then DhcpInfo, and
HomeTab tries the phone whenever an address is known. New diagnostics:
- Tablet Home, small line under the status: `Phone address <ip> (typed|route|dhcp)` plus tablet Wi-Fi dBm.
- Phone tab: `Requests received: N. Last: <ip> <path>: <result> at <time>` (Api.note), refreshed every 2 s.
Waiting for the user to reinstall on both devices and report those two lines. Read them to tell apart:
no address found / tablet cannot reach the phone / phone refuses pairing / Pair window closed.

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

## Protocol (plain sockets, HTTP/1.0 style, port 8765)

Header `X-Token: <32 hex>`. The tablet's token is its own secret; the phone keeps the secrets of
paired tablets. Phone endpoints: `/s` (status), `/pair` (POST, only while the 2-minute Pair window
is open), `/act?name=` (POST), `/probe`, `/speed/{start,stop,status}`, `/cells`, `/band?set=`,
`/band/keep`, `/bandinfo`. Both devices: `/fs/{ls,get,put,mkdir,rm}`. The phone remembers the last
tablet's IP and token (Cfg.setTablet) so the phone can browse the tablet's files; the tablet's
server only runs if "Let the phone browse this tablet's files" is on.

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

app/src/main/java/com/tj/linkmeter: Cfg (prefs, trust), Http/Client (sockets), Api (router),
SenderService (service), BootReceiver, Status, Priv/Actions/Eco/Cells/ShellMain (privileged),
SpeedTest, Files, Net, Call (tablet helpers), Ui, MeterView, SpeedView, Tab and
HomeTab/SpeedTab/BandsTab/ControlTab/FilesTab/SettingsTab/PhoneTab, MainActivity.
Tools: `tools/ci.sh` (commit, push, watch build), `tools/checkpoint.sh` (commit and push only).
`gh run view <id> --repo tjshea90/LinkMeter --log-failed` shows a failed build.
