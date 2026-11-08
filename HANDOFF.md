# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. A session can be cut off at any time, so after every
meaningful step run `tools/checkpoint.sh "what changed"` (commit + push, retries the push, warns when this file is stale)
and keep the **RESUME HERE** block below true. Write the explanation here BEFORE the big commit, not after: the last
session died between its push and its write-up, and the next one had to rebuild the story from git and from the dead
session's transcript.

## RESUME HERE (newest session first)

**2026-11-08, second session "Faster remote control update"**: the work is on branch `ccr-ae02e85b-7bro73` and is NOT merged
to `main` yet (`main` has the first remote-control fix only). It compiles green in CI and the offline tests pass
(`tools/test.sh`). **None of the new interface or phone-settings code has run on a device yet.**

What the user said after trying the first build: the live remote control "works very well"; it fell back to Pictures once or
twice; once it said "the decoder stopped taking video" and the picture only came back when they moved a finger (a reconnect
whose first picture the tablet decoder held back until more frames came). They asked for, and the branch now has:
1. Advanced video options (resolution, bitrate limit, decoder, low-delay tuning, pictures speed, speed numbers). A frames per
   second cap for LIVE VIDEO is not possible: the phone's `screenrecord` has no such option (see "Remote screen control").
2. A new interface: a column of buttons on each side of the picture, never over it (the old bottom bar covered the phone's own
   navigation bar), a typing mode that opens the tablet's keyboard without covering the picture, a number pad for the PIN, and an
   Options panel.
3. The decoder problem: the stream header is rewritten so the decoder does not hold pictures back (`SpsFix`), low-latency hints,
   and a sharp still picture whenever the phone's screen stops changing.
4. While the remote screen is open the phone keeps its screen on for at least 2 minutes, dims to the lowest brightness, and puts
   everything back afterwards (`RemoteProfile`, with its decisions in `ProfilePlan`).
5. The two dead network-adb buttons, `AdbClient` and the `adb_tcp_setup` action are gone.

NEXT, in order: (a) ask the user whether to merge the branch into `main` (that publishes `latest`); (b) the user updates both
devices and field-tests with the checklist in "Remote screen control", then reports what the Options panel's "Copy this
information" button gives; (c) fix what it shows.

Earlier the same day (first session, `session_01UWi8ZxcnuAwP2jZg1uh8Gh` and the one after it): the first version of the live
remote control (commit `14b3e12`) was written by a session that died of "Prompt is too long" before writing it down; the next
session found that `Api.handle` never routed `/screen/video` and `/screen/ctl` (they answered 404, so the tablet silently used
Pictures), fixed that, hardened the video and touch paths, and merged it into `main` as `latest` (`088cc22`).

Branch rule for cloud sessions: develop and push only on the designated `ccr-*` branch, and merge into `main` only when the
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
| Screen tab, live H.264 video + live touch (first version) | Screen.video/ctl, VideoStream, CtlChannel, ShellMain inject | Shizuku (screenrecord, app_process helper) | CONFIRMED by the user: works very well |
| Screen tab, new interface (side bars, typing, number pad, Options, sharp still, header fix) | ScreenTab, RemoteLayout, TypingView, PinPad, AdvancedPanel, SpsFix | same | LOW until field-tested |
| Phone screen kept on and dimmed during remote use, restored after | RemoteProfile, ProfilePlan, Eco | Shizuku (`settings put system ...`) | LOW until field-tested (logic tested offline) |

## Where things stand (latest, replaces the old "open issue" note)

Working on the user's devices: pairing, Home meters, Speed test (download ok; upload is limited by the user's weak
carrier uplink, not the test), Files, Restore everything (verified), ShellMain telephony helper (Read lock info shows
real values), band lock (the modem ACCEPTED a lock request), Probe, Prepare phone, and (confirmed 2026-11-08) updating
the phone from the tablet: "Update phone from this tablet" worked, both devices were on 0.2.25 and the phone said "Success".
Also confirmed 2026-11-08: the live remote control (video + live touch, build `088cc22`) "works very well", with the
occasional fall back to Pictures and one "decoder stopped taking video" that cleared when a finger moved (see the section below).
Built, not yet confirmed on the devices: hotspot band chooser + restart, battery saver profile, Force 4G / Allow 5G via
the API route, Guard auto-undo, signal fallback through Shizuku (the "no signal" meter on Home), and everything added to the
remote screen on the unmerged branch (new interface, Options, typing, header fix, sharp still, phone screen kept on and dimmed).

Removed 2026-11-08 at the user's request: the "Set up network adb" and "Start Shizuku" buttons (Control tab), AdbClient and the
phone action `adb_tcp_setup`. They could not work on this phone: it refuses `Failed to set property 'persist.adb.tcp.port'`,
so LinkMeter cannot turn network adb on, and `adb tcpip 5555` from a computer only lasts until the next reboot. After a phone
restart Shizuku still has to be started the usual way (computer over USB, or the tablet-hotspot trick: join the phone to the
tablet's hotspot once so it reconnects by itself).

Open items, in order:
1. Merge the branch (needs the user's say-so), then field test the new remote screen interface and the phone-settings
   session, see "Remote screen control".
2. Bands tab was rebuilt: grid of LTE (29) and NR (27) band buttons, a status card with Keep / Clear and a countdown,
   "Now using" from a scan 6 s after each lock. Needs a field test: which bands the modem accepts, which gives the best
   upload (the user's goal is better indoor upload; the phone sat on LTE band 66 with sinr about 4; try 13, 5, 2, 4).
3. Speed test servers: Cloudflare first, then Hetzner (ash-speed) and OVH as fallbacks after HTTP 429 and timeouts
   hit the user's connection. The two fallbacks are unverified. Upload exists on Cloudflare only.
4. Ask the user what his BandLock app already does to change bands.
5. Keep HANDOFF.md current; run `tools/checkpoint.sh` after each change.

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

Design copied from scrcpy: live hardware video plus a persistent touch channel, instead of one screenshot and one `input`
process per event. Everything needs Shizuku on the phone and the phone setting "Let the tablet see and control this screen"
(on by default). Nothing runs unless the tablet's Screen tab is open.

### Phone side (`Screen.java`, routed in `Api.handle`; every route needs the paired token)
- `GET /screen/video?w=<px>&br=<bit/s>`: a long reply. Headers `X-Size: <phoneW>x<phoneH>` and `X-Video: <w>x<h>` (both video
  sides are multiples of 16). Body = packets `[int32 big-endian length][length bytes of Annex-B H.264]`. A negative length means
  "the next -length bytes are a UTF-8 message" (why video could not start; it ends the stream). Length 0 is ignored.
  Source: `exec screenrecord --output-format=h264 --size WxH --bit-rate N - 2>/data/local/tmp/linkmeter-screenrecord.err`
  started through `Priv.spawn`. screenrecord stops after 3 minutes, so it is restarted (the stream then carries new SPS/PPS
  and a keyframe). Packets are cut by timing: when no more bytes are readable 3 ms after a read, what has been read is sent
  as one packet (a guess that one encoder write is one frame). A watcher thread ends screenrecord when the tablet closes the
  connection. Two quick failures in a row send the screenrecord error text and end the stream.
- `GET /screen/ctl`: a long connection. The tablet sends lines `d x y`, `m x y`, `u x y` (finger down, move, up, in the phone's
  pixels), `k code` (keys in `Screen.KEYS`: home, back, volume, power, menu, recents, sleep, wake, arrows, enter, tab, escape,
  backspace, delete, page and home/end keys), `t <hex>` (typed text, 4 hex digits per character, at most 60 characters per
  line), `p` (keepalive, every minute, also renews the session lease). The phone feeds them to `ShellMain inject`, a helper
  started with `app_process` as the shell user: touches become `MotionEvent`s through `InputManager.injectInputEvent` (a few
  ms each instead of a new `input` process), text becomes the key events the system keyboard map gives for each character
  (characters it has no key for are skipped; newline is Enter). The helper is started with `exec`, lives only while a touch
  connection is open, and a connection that ends while a finger is down lifts the finger. A silent connection is dropped after
  3 minutes.
- `GET /screen/frame?w=&q=`: Pictures mode and the sharp still. `screencap` raw (16-byte header: width, height, format,
  dataspace, then RGBA) is scaled and sent as JPEG; it falls back to `screencap -p` into a file. One-shot actions
  `/screen/tap|swipe|key|on|off|text|pin` are the slow path used when the touch channel is not up. `/screen/state` answers
  `{"on":..,"locked":..,"prof":..}` and is polled every 3 s while the tab is open (it also renews the session lease).
- `POST /screen/profile?keep=<ms>&dim=<0..255 or -1>` starts or updates the phone-side session, `POST ?end=1` ends it, `GET`
  says what it is doing. See "The phone while the remote screen is open".

### Tablet side
- Layout (`RemoteLayout`): the picture in the middle and a 56 dp column of buttons on each side, so no button is ever over the
  picture. Left: Apps (recents), Home, Back, volume up and down, and at the bottom the speed numbers. Right: Type, Wake/Sleep,
  Unlock (lit when the phone is locked), Options, Full/Exit. On the user's tablet (600x973 px) a portrait phone picture leaves
  unused room on both sides anyway. A header with the picture mode, the quality preset and the status line is shown except in full
  screen and while typing. Icons are drawn in code (`Icons`, looked at with `tools/test/IconSheet.java`).
- Full screen: system bars hidden WITHOUT the "layout" flags (they stop the window shrinking when the keyboard opens) and hidden
  again when the window regains focus. Back leaves full screen.
- Typing: Type hides the tab bar and header, swaps the right column for Done, Enter, Backspace, Tab and arrow keys, and opens
  the tablet's keyboard on an invisible `TypingView` that forwards characters (as `t` lines) and keys (`k` lines) as they are
  typed, with suggestions switched off. `RemoteLayout` keeps the picture at the size it had and anchored at the top, so only
  the bottom of it, where the phone's own keyboard and navigation bar are, goes under the tablet's keyboard. Closing the
  keyboard with its own Back button ends typing mode by itself. A physical keyboard works too. Only characters the phone's
  keyboard map has a key for can be typed (Latin letters, digits, punctuation).
- PIN: an in-app number pad (`PinPad`), so unlocking never needs the keyboard. Sent once to `/screen/pin`, never stored.
- Tapping the picture of a sleeping phone wakes it (an option).
- Video (`VideoStream`): a MediaCodec decoder on the SurfaceView, every decoded buffer released at once. Parameter sets that
  arrive alone are held and sent with the next picture. With "tuning" (default): the SPS is rewritten by `SpsFix` so the
  decoder is told pictures come out in order and few are kept, and low-latency hints are set (including MediaTek-unknown
  vendor keys, which are ignored if unknown); a decoder that refuses the hints is started plain. In Automatic mode, if pictures
  arrive but the decoder shows none for 8 s, the tab retries once without tuning before falling back to Pictures. If the decoder
  stops taking input for 3 s the stream restarts (clean keyframe). A lost stream reconnects quietly after 0.6 s and the last
  picture stays on screen; after 3 failures a note appears.
- Sharp still: when no video packet has arrived for 450 ms, one JPEG (at least 720 px wide, quality 75) is fetched from
  `/screen/frame` and shown over the video, so a screen that stopped changing never depends on the decoder letting go of its
  last pictures. It goes away 120 ms (or 4 packets) after the screen moves again. Both views fit the phone's own shape, so
  touches map exactly.
- Pictures mode: JPEGs one after another (at most N per second if set). If video shows no picture within 8 s or the phone
  refuses, the tab uses Pictures for that visit and says why (the saved choice stays).
- Options panel (`AdvancedPanel`, drawn inside the app over the picture): mode, quality preset (Saver / Fast / Balanced / Sharp /
  Custom), video width, video bitrate limit, pictures width / quality / per second, sharp still on or off, speed numbers, low-delay
  tuning (Automatic / Always / Never), decoder choice, how long to keep the phone's screen on, phone brightness, sleep on leave,
  tap to wake, "Put the phone's screen settings back now", and an Information box with "Copy this information" (decoder, stream
  header before and after the fix, rates, last problem, phone settings) for bug reports. Settings are in `ScreenPrefs`.
  The video bitrate is the phone's encoder TARGET (`--bit-rate`); busy screens can briefly go above it (the Information box
  shows the peak of the last 10 s).
- A frames-per-second CAP for live video does not exist: `screenrecord` has no option for it (only size and bit rate), and the
  encoder's frame rate follows the phone's display. What would unlock it (and a hard bitrate cap, live bitrate changes, on-demand
  keyframes, and frames repeated while the screen is still): an encoder of our own inside `ShellMain` (a virtual display plus
  `MediaCodec` with `max-fps-to-encoder`, `repeat-previous-frame-after`, CBR), the way scrcpy does it. It needs hidden display
  APIs that cannot be tried in the sandbox, so make it an opt-in engine that falls back to screenrecord on any error.

### The phone while the remote screen is open (`RemoteProfile`, decisions in `ProfilePlan`)
- On entering the tab the tablet sends `/screen/profile?keep=<ms>&dim=<level>`: the phone makes sure `screen_off_timeout` is at
  least `keep` (default 2 min; unchanged if it is already longer) and sets `screen_brightness_mode=0` and `screen_brightness` to
  the chosen level (default 0, the lowest). The tablet's picture is not dimmed: it comes from the screen contents.
- On leaving the tab (or the app going to the background) it sends `?end=1` and the phone puts the settings back.
- Safety (the user's own settings): the originals are saved before anything changes; a setting is only put back if it still has
  the value we gave it (a value the user changed by hand meanwhile is kept); if the tablet disappears (no request for 60 s and no
  stream open) the phone restores by itself; a service restart restores anything left over; the tablet sends begin and end
  through one ordered queue so "end" can never overtake "begin".
- The battery saver (`Eco`) also sets a 15 s screen timeout. While a session owns the timeout Eco leaves it alone and remembers
  the original; when the session ends the timeout goes back to Eco's value if Eco is on. Both take the `Eco.class` lock.
- If the phone ignores `settings put system screen_brightness` (Samsung may use another key) the Options panel shows what the
  phone kept ("Brightness is ... (it was ...)"). Brightness 0 may be clamped to the phone's minimum or look black on an AMOLED
  panel; "Very low" is the next choice.

### Tests (offline, in this sandbox)
`tools/test.sh` runs: `tools/test/ProfilePlanTest.java` (the phone-settings decisions: explicit scenarios plus 5000 random
begin / change / end stories that must give the phone back exactly as it was) and `tools/test-spsfix.sh` (SpsFix against real
x264 streams of many kinds, checked by ffmpeg's own header parser and decoder). Android code itself can only be compiled by CI.

### Field test (the user does this with both devices in hand; this sandbox cannot)
1. Put the same build on both devices (install on the tablet, then Control > Update phone from this tablet). Open Screen.
2. The phone's brightness should drop and its screen should stay on for 2 minutes; the picture on the tablet stays normal. Leave
   the tab: brightness and the timeout should go back to what they were (check the phone's Display settings).
3. Options > Information: note the decoder name and the stream line (profile, reorder, buffer) before and after. Report them.
4. Move around, stop, wait: the picture should stay correct when the phone's screen goes still (the sharp still shows after
   about half a second). Leave the phone alone for a minute and touch it again.
5. Type: tap Type, type in a phone text field with the tablet keyboard, use Enter and Backspace; check the picture stays big and
   the left column stays usable; close the keyboard with its own Back button.
6. Full screen: the phone's own navigation bar must be visible and tappable; try the PIN pad on a locked phone.
7. Options: change width and bitrate, watch the speed numbers; try another decoder; try Pictures mode and its speed limit.
8. After leaving the tab, check on the phone (Shizuku shell or Probe) that nothing lingers:
   `ps -A | grep -E "screenrecord|app_process"` should show no screenrecord and no `ShellMain inject`.

### Likely trouble spots, with the remedy to try
- The picture still sticks until the screen changes: look at Information (does the stream line say "reorder 0, buffer 1" after
  the fix?), try the other decoder in Options, try "Never" and "Always" for tuning. Next ideas: replay the first keyframe with a
  changed `idr_pic_id` to push the decoder, or the own-encoder engine (frames repeated while the screen is still).
- Smeared or garbled picture after fast motion: a frame was split by the 3 ms timing guess. Fix: let the tablet cut the stream
  into pictures by parsing NAL units (a slice with `first_mb_in_slice == 0` starts a new picture) instead.
- A hiccup every 3 minutes: the screenrecord restart. Expected; could be hidden by starting the next one early.
- The phone is rotated while streaming: the stream keeps the size it started with, so the picture and the touch mapping go wrong
  until the tab is left and re-entered. Fix if it matters: restart the stream when the phone's size changes.
- Typed text goes wrong with some keyboards (composing text): `TypingView` turns suggestions off and replaces drafts by deleting
  and retyping; if a keyboard still misbehaves, look at which InputConnection call it uses.
- screenrecord refuses on this Samsung build (the status line will say what it printed): the last resort is the own-encoder engine.

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
- Offline tests: `tools/test.sh` (see "Remote screen control"). This sandbox has `ffmpeg` with libx264 and the `trace_headers`
  filter, which is how SpsFix is checked against real streams and ffmpeg's own parser. Pure logic should live in classes
  without Android imports (SpsFix, ProfilePlan, Icons) so it can be tested here. To look at the icons:
  `javac -d <scratch> app/src/main/java/com/tj/linkmeter/Icons.java tools/test/IconSheet.java` then
  `java -cp <scratch> com.tj.linkmeter.IconSheet <scratch>/sheet.png` and open the PNG.
- CI compiles but cannot see a route, a permission or a lock that is simply missing. Trace each new feature end to end
  (tablet request -> `Api.handle` -> handler -> reply -> tablet parse) before calling it done.
- Keep one session's context small: a session that read and wrote about 970K tokens died of "Prompt is too long".
  Checkpoint, update this file, and continue in a fresh session instead of pushing on.

## Protocol (plain sockets, HTTP/1.0 style, port 8765)

Header `X-Token: <32 hex>`. The tablet's token is its own secret; the phone keeps the secrets of
paired tablets. Phone endpoints: `/s` (status), `/pair` (POST, only while the 2-minute Pair window
is open), `/act?name=` (POST), `/probe`, `/speed/{start,stop,status}`, `/cells`, `/band?set=`,
`/band/keep`, `/bandinfo`, `/apk` (POST, tablet-driven update), and the screen routes
`/screen/{state,frame,video,ctl,profile,tap,swipe,key,on,off,text,pin}` (see "Remote screen control").
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
Call (tablet helpers), Updater (tablet-driven phone update), Ui, MeterView, SpeedView, Tab,
HomeTab/SpeedTab/BandsTab/ControlTab/FilesTab/SettingsTab/PhoneTab/ScreenTab, MainActivity.
Remote screen, phone: Screen (routes), RemoteProfile and ProfilePlan (screen kept on and dimmed, restored), ShellMain `inject`.
Remote screen, tablet: ScreenTab (the tab), RemoteLayout, BarColumn, BarButton, Icons (layout and buttons), TypingView, PinPad,
AdvancedPanel, ScreenPrefs (settings), VideoStream, SpsFix (decoder), CtlChannel, AspectSurfaceView, PhoneScreenView.
Tools: `tools/checkpoint.sh` (log, commit, push with retries), `tools/ci.sh` (same, then watches the build with gh),
`tools/handoff-check.sh` (warns when this file is stale), `tools/test.sh` (offline tests). `CHECKPOINTS.md` is the append-only log they write.
