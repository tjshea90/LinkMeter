# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. A session can be cut off at any time, so after every
meaningful step run `tools/checkpoint.sh "what changed"` (commit + push, retries the push, warns when this file is stale)
and keep the **RESUME HERE** block below true. Write the explanation here BEFORE the big commit, not after: the last
session died between its push and its write-up, and the next one had to rebuild the story from git and from the dead
session's transcript.

## RESUME HERE (newest session first)

**2026-11-08 (even later): the user's report on v0.2.51, "the screen still dims quickly on the tablet, and a lot of times the
sleep button doesn't work". Changed in code, NOT yet seen on a device; read "Field test" items 11 and 12.**
- Sleep. Found by reading the code (no device here): the phone answered "Screen off." as soon as `input keyevent 223` exited,
  without checking that it slept; the tablet only relabelled the button, so with live video the last frame stayed up as if the
  phone were awake (screenrecord sends nothing while the display is off, so nothing told the tablet); `phoneOn` was flipped
  optimistically and overwritten by a 3 s poll that could still carry the old answer; and the phone's server dropped a connection
  silently when all 8 workers were busy. Now: `Screen.power` checks `PowerManager.isInteractive()` after the key, retries with
  the power key (which toggles, so only while the state is still wrong) and answers `{"ok":true,"on":bool,"out":..}` or a 409
  that says it did not work; tablet `PhonePower` (pure, `tools/test/PhonePowerTest.java`) decides what to believe (no answer
  about the phone's state counts while a press is in flight or for 3 s after it); `ScreenTab.phoneWentOff()` stops the video and
  shows "The phone's screen is off. Tap Wake." (also when the phone falls asleep by itself); a failed press is retried once and
  then shown in a toast; a touch within 1.5 s after Sleep does not wake the phone; the server has 16 workers and answers 503
  "busy" instead of hanging up.
- Tablet dimming. v0.2.51 already held `FLAG_KEEP_SCREEN_ON` while the Screen tab is shown and I could find no bug in it (checked:
  the default of `Cfg.getBool` is honoured, `onPause`/`onResume` call `setHoldAwake` in the right order, no other code clears the
  flag). The cause on the user's tablet is therefore still UNKNOWN: an old APK on the tablet? an OEM power manager? Added: a
  second request through the view flag, a bright-screen wake lock that is renewed while the tab is open and runs out by itself
  after 10 minutes (permission `WAKE_LOCK`), the tablet's and the phone's versions at the top of Options, and an Information line
  "Tablet screen: held on by the Screen tab (window flag set, wake lock held); Android's own screen timeout N s, brightness ..,
  battery saver ..". If it still dims, ask for that Information text: flag set + wake lock held + still dimming means the tablet's
  own firmware overrides apps.

**2026-11-08 (later): the user's field report on v0.2.47 is fixed in code, released as v0.2.51 (NOT yet seen on a device).** Report: the picture
sometimes showed blocks of old and new screen mixed while a page loaded (screenshot), and the TABLET dimmed after a short idle.
- Garbled picture = a wrong reference picture in the decoder until the next keyframe (screenrecord sends one only every 10 s).
  Cause: `Screen.video` (phone) cut `screenrecord`'s raw byte stream into "frames" with a 3 ms "pipe went quiet" guess, and
  `VideoStream` (tablet) fed every packet to the decoder as one picture. Under load (the Shizuku process that relays the stream
  adds gaps too) a big picture arrived in two pieces or two pictures arrived as one; headless fragments were held as "parameter
  sets", and a too-big picture was silently dropped. All of those leave the decoder drifting.
- Fix: (1) NEW `AnnexBFramer` (tablet, pure Java) cuts the byte stream into whole pictures by NAL type and `first_mb_in_slice`;
  the phone's packet end is only a hint that the stream went quiet there (it releases the last picture of a burst, after a
  plausibility check). If a picture was cut anyway it reports damage (the rest has no start code, or a slice without its
  picture start). Tested in `tools/test/test_framer.py` against ffprobe's picture boundaries on 10 real x264 streams (multi-slice,
  B-frames, AUD, repeated headers, intra refresh, big keyframes, static screen, CBR with filler) plus hand-built and fuzzed
  streams; five deliberate breakages of the parser are caught. (2) `VideoStream` feeds one picture per decoder buffer, ends
  the stream as "damaged" on damage (`Events.onDamaged`), reports an oversize picture instead of dropping it, and ends at once
  when the decoder fails. `ScreenTab` then covers the picture with a sharp still and restarts the video after 0.1 s (a
  keyframe comes first); 4 damaged restarts in a row (each within 8 s) fall back to Pictures. (3) Phone: after a read the
  stream is sent when the pipe has been quiet for 3 ms (small picture) or 12 ms (more than 3000 bytes in the packet so far).
  (4) The tablet keeps its own screen on and bright while the Screen tab is shown (`MainActivity.setHoldAwake`; Options >
  "This tablet while you use this", `ScreenPrefs.tabletAwake`, default on); the old Settings switch for the whole app stays.
- Please check on the devices (details in "Field test", items 9 and 10): no more mixed blocks during page loads; Options >
  Information shows "Video restarts because a picture arrived damaged: N" (0 or rare is the goal) and a "Phone app:" line
  that must say "same as this tablet"; the tablet stays bright when left alone.
- The real cure for all framing and keyframe problems is the own-encoder "Engine 2" (see "Remote screen control").

**2026-11-08, "Faster remote control update", last part**: everything described below is MERGED into `main` (the user said
"Merge it"), and from now on builds are NUMBERED RELEASES that merge and publish themselves.

1. Releases are numbered and automatic (the user's standing instruction). Every push to `main` or to a `ccr-*` session branch
   is built and tested by GitHub (`.github/workflows/build.yml`). A good build on a session branch is fast-forwarded into
   `main` by the pipeline when that is safe, and a numbered release (`v0.2.N` with `LinkMeter-0.2.N.apk`) is published if the
   app changed. Sessions therefore no longer ask "merge it?". Rules, switches and limits: "Releases and the automatic merge".
   Releases page: https://github.com/tjshea90/LinkMeter/releases
2. What the user asked for in this round, all in that merge (details in "Remote screen control"): advanced video options
   (resolution, bitrate limit, decoder, low-delay tuning; a frames per second cap for live video is impossible with the
   phone's `screenrecord`), a new remote screen interface (side columns of buttons, typing mode, number pad, Options panel),
   a fix for the decoder holding pictures back (header fix, low-latency hints, sharp still picture), the phone keeping its
   screen on 2+ minutes and dimming while the remote screen is open and putting everything back (`RemoteProfile`), and
   removal of the dead network-adb buttons.
3. The first remote-control build was confirmed by the user ("works very well"). Everything added since then has compiled
   and passed the offline tests (`tools/test.sh`) but has NOT run on a device yet.

NEXT: the user installs the newest release (v0.2.51 or later; `https://github.com/tjshea90/LinkMeter/releases/latest/download/LinkMeter.apk`
is always the newest) on the tablet, then Control > Update phone from this tablet, and goes through the field-test checklist in
"Remote screen control"; the Options panel's "Copy this information" gives what to report (its "Phone app:" line says whether the
phone really runs the same build). Then fix what it shows.

Branch rule for cloud sessions: develop and push only on the designated `ccr-*` branch; the pipeline does the merging. Start
every session with `git fetch --all && git branch -r && git log --oneline main..origin/<newest ccr-* branch>` so work that sits
on an unmerged branch is not mistaken for missing (the pipeline leaves a branch unmerged when it touches `.github/`, when
`main` has moved, or when told so by a marker: then merge by hand, with the user's say-so).

Earlier the same day: the first version of the live remote control (commit `14b3e12`) was written by a session that died of
"Prompt is too long" before writing it down; the next session found that `Api.handle` never routed `/screen/video` and
`/screen/ctl` (they answered 404, so the tablet silently used Pictures), fixed that, hardened the video and touch paths, and
merged it into `main` as the `latest` release (`088cc22`).

## What it is

One Android app (`com.tj.linkmeter`, pure Java, no UI libraries) installed on two devices.
The user's phone is the hotspot; the tablet joins it and controls the phone.

- **Phone**: Samsung Galaxy A15 5G, Android 16, not rooted, Total Wireless (Verizon). Runs a
  small always-on foreground service (starts at boot) that answers the tablet over the hotspot.
  Privileged things go through **Shizuku** (the user already runs it for his BandLock app).
- **Tablet**: MediaTek MT8168 Go, Android 10 (API 29), ARMv7, 128 MB heap, 600x973 @170 dpi,
  font scale 1.2, no Play services. Tabs: Home, Speed, Bands, Control, Files, Settings.
  Only the tab on screen does work, and only while the app is in front.

Repo: github.com/tjshea90/LinkMeter. Every push is built and tested; good builds become numbered releases (`v0.2.N`, file
`LinkMeter-0.2.N.apk`) at https://github.com/tjshea90/LinkMeter/releases (see "Releases and the automatic merge").

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
| Screen tab: exact picture cutting, restart on damage, tablet stays awake | AnnexBFramer, VideoStream, ScreenTab, Screen.video, MainActivity | same | LOW until field-tested (offline tests pass) |
| Screen tab: Sleep / Wake that checks itself, "phone is off" state, backup wake lock, busy reply | PhonePower, ScreenTab, Screen.power, MainActivity, SenderService | same | LOW until field-tested (offline tests pass) |
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
1. Field test the new remote screen interface and the phone-settings session, see "Remote screen control".
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
  and a keyframe). Packets are cut by timing only as a hint: after a read, what has been read so far is sent when the pipe
  stays empty for 3 ms (12 ms if the packet is already over 3000 bytes: big pictures can arrive in pieces with gaps), or when
  2 MB have piled up. A packet may hold several pictures or half of one: the tablet treats the packets as a byte stream and
  finds the pictures itself (`AnnexBFramer`). A watcher thread ends screenrecord when the tablet closes the
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
  `/screen/tap|swipe|key|text|pin` are the slow path used when the touch channel is not up. `/screen/on` and `/screen/off` wake and
  sleep the phone and CHECK it (`Screen.power`): key 224 / 223, wait up to 1.5 s for `isInteractive()` to follow, then the power
  key (26, a toggle) if it is still wrong; reply `{"ok":true,"on":<real state>,"out":..}`, or 409 "did not wake up / go to
  sleep". `/screen/state` answers `{"on":..,"locked":..,"prof":..,"vn":"<app version>"}` and is polled every 3 s while the tab is
  open (it also renews the session lease). The phone's server (`SenderService`) has 16 workers and no queue; when all are busy it
  answers 503 "busy" (it used to hang up silently). The video and touch connections each hold a worker for as long as they are open.
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
- Video (`VideoStream`): a MediaCodec decoder on the SurfaceView, every decoded buffer released at once. The packets go
  through `AnnexBFramer`, which hands the decoder exactly one picture (access unit) per input buffer, with its parameter sets
  and SEI in front: a picture ends where an AUD / SPS / PPS / SEI starts after a slice, or where a slice with
  `first_mb_in_slice == 0` starts (the first bit after the NAL header byte is 1). The last picture of a burst has no successor,
  so the phone's packet end releases it unless its last NAL unit is a bare start code or a stub slice; if that guess was
  wrong the stream is reported damaged (a continuation without a start code, or a slice that is not the first of its
  picture) and restarted, instead of showing mixed-up blocks until the next keyframe. A cut filler or end-of-sequence NAL
  unit is not damage (no picture is lost). With "tuning" (default): the SPS is rewritten by `SpsFix` so the
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
  header before and after the fix, rates, last problem, phone settings, and the app version on the tablet and on the phone:
  the phone says its version in the `vn` field of `/screen/state`, builds before 0.2.47 do not, and then the box says "version
  not reported (older build)", the usual sign that the phone was not updated) for bug reports. Settings are in `ScreenPrefs`.
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
begin / change / end stories that must give the phone back exactly as it was), `tools/test/PhonePowerTest.java` (the Sleep / Wake
button rules: stale answers are not believed, failures revert, a lost reply is corrected later; 3000 random stories) and
`tools/test-spsfix.sh` (SpsFix against real
x264 streams of many kinds, checked by ffmpeg's own header parser and decoder) and `tools/test-framer.sh`
(`tools/test/AnnexBFramerTest.java` + `test_framer.py`: AnnexBFramer against ffprobe's picture boundaries on real x264 streams
and against streams built by hand; fed as pictures, merged, in odd pieces, byte by byte, without hints, with hints inside start
codes, with false hints in the middle of pictures (every wrong picture must be followed by a damage report), joined mid-picture,
with junk in front, and fuzzed). Android code itself can only be compiled by CI.

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
9. Garbled picture: load a heavy page on the phone (the screenshot showed a page with a "Page is loading" overlay while a
   download ran), scroll, switch apps quickly. The picture must not stay full of mixed old/new blocks. Options > Information:
   "Video restarts because a picture arrived damaged" should be 0 or small, and "Phone app:" must say "same as this tablet".
   If a restart happens the status line says "Picture damaged: restarting the video for a clean one…" and a sharp still covers
   the picture for about a second.
10. The tablet's screen: open Screen, leave the tablet untouched for longer than its screen timeout (the phone can be changing
   meanwhile): it must stay on and bright. Switch Options > "Keep this tablet's screen on and bright" off and it should dim
   as before. Leaving the tab hands the screen back to Android's timeout.
11. Sleep and Wake, ten times each, in Live video and in Pictures mode: after Sleep the status line says "Putting the phone to
   sleep…", then within about two seconds the picture is replaced by "The phone's screen is off. Tap Wake." and the phone's
   screen is really off; Wake brings the picture back within a couple of seconds. Touching the picture just after Sleep must not
   wake the phone. If a press fails a toast says why ("The phone did not go to sleep ..."): write the text down.
12. If the tablet still dims: Options > Information > "Tablet screen: ..." line, and the top of Options (it says which
   build the tablet and the phone run). Flag set, wake lock held, Android timeout N s, and it still dims = the tablet's own firmware.

### Likely trouble spots, with the remedy to try
- The picture still sticks until the screen changes: look at Information (does the stream line say "reorder 0, buffer 1" after
  the fix?), try the other decoder in Options, try "Never" and "Always" for tuning. Next ideas: replay the first keyframe with a
  changed `idr_pic_id` to push the decoder, or the own-encoder engine (frames repeated while the screen is still).
- Smeared or garbled picture after fast motion or a page load: was a picture split or merged by the phone's 3 ms timing guess;
  fixed by `AnnexBFramer` (see above). If it still happens: Information > "Video restarts ..." counts the cases the framer
  noticed; garbage WITHOUT restarts means the decoder itself drifted (try another decoder, try tuning "Never"), or the
  phone's encoder emits something the framer reads wrongly (multi-slice pictures sent in arbitrary slice order, redundant
  pictures). A restart loop shows as "the video keeps arriving damaged" and Pictures mode.
- A hiccup every 3 minutes: the screenrecord restart. Expected; could be hidden by starting the next one early.
- The phone is rotated while streaming: the stream keeps the size it started with, so the picture and the touch mapping go wrong
  until the tab is left and re-entered. Fix if it matters: restart the stream when the phone's size changes.
- Typed text goes wrong with some keyboards (composing text): `TypingView` turns suggestions off and replaces drafts by deleting
  and retyping; if a keyboard still misbehaves, look at which InputConnection call it uses.
- screenrecord refuses on this Samsung build (the status line will say what it printed): the last resort is the own-encoder engine.

## Releases and the automatic merge (`.github/workflows/build.yml`)

Three jobs run on every push to `main` or `ccr-*` (and on manual runs):
- `build`: Gradle `assembleRelease`. The APK is renamed `LinkMeter-<version>.apk`, and its `versionName` is read back with
  `aapt2` and must equal the release version (so `gradle.properties` and the workflow cannot drift apart).
- `test`: `tools/test.sh` (ffmpeg is installed first): the phone-settings tests, the H.264 header tests, and the tests of the
  merge gate below.
- `release`, only if both passed, one at a time: the step "Decide what to do" works out two answers.
  - Merge (only for a `ccr-*` branch): the commit must be a fast-forward of `main` (main must not have moved, and must not
    already contain it), must not touch `.github/`, `app/debug.keystore` or the `applicationId` line of `app/build.gradle`,
    and its message must not contain `[no-merge]` or `[wip]`. Then `git push origin HEAD:main`. That push is made with the
    workflow's own token, which by GitHub's rule does not start another run, so there is one release per commit.
  - Release (after a merge, or for a push to `main`): only if something under `app/`, `gradle/`, `build.gradle`,
    `settings.gradle` or `gradle.properties` changed since the previous release tag, and the message has no `[no-release]`.
    Tag `v<version>`, title `LinkMeter <version>`, assets `LinkMeter-<version>.apk` and the same file as `LinkMeter.apk` (so
    `https://github.com/tjshea90/LinkMeter/releases/latest/download/LinkMeter.apk` is always the newest), notes = the commit
    subjects since the previous release.
- Version: `versionBase` in `gradle.properties` (0.2 now) plus the workflow's run number is both `versionName` and
  `versionCode` (so numbers skip when runs release nothing, and always rise). Raise `versionBase` for a bigger step.
- Markers in the head commit's message: `[no-release]` (merge, no release; `NO_RELEASE=1 tools/checkpoint.sh` adds it),
  `[no-merge]` or `[wip]` (neither), `[skip ci]` (GitHub's own: nothing runs at all, not even the merge, so avoid it now).
- Manual runs (Actions > build-apk > Run workflow, or the MCP `actions_run_trigger` with `ref` and string inputs):
  `dry_run` (only report, and test that main could be pushed), `rehearse` (makes a DRAFT release with the real APK and
  deletes it at once, merges nothing: use it after changing the release step), `force_release`.
- A change to the workflow is never merged by the pipeline (the workflow's token may not change workflow files, and nothing
  tests a workflow but running it). Merge it by hand with the user's say-so (`git push origin HEAD:main` once the branch run is
  green), rehearse first, then watch the run on `main`.
- `tools/test/test_release_decision.py` runs the real "Decide what to do" script, taken out of the workflow file, in
  throwaway git repositories (24 cases). Change the gate only together with that test. `actionlint` (`pip install
  actionlint-py`) lints workflow files.
- Proof (2026-11-08, checked on GitHub, not just in the run log): the hand merge of this branch into `main` made release
  `v0.2.46` (assets `LinkMeter-0.2.46.apk` and `LinkMeter.apk`, notes from the commit subjects, old `latest` release and tag
  deleted). Then the first FULLY automatic cycle: a plain push (`97fd7b8`, the phone-app-version line in the Information box) to
  the session branch fast-forwarded `main` and published `v0.2.47` with nothing done by hand; the pipeline's own push to `main`
  started no second run, so there is one release per commit. A docs-only push is merged but releases nothing (the decision
  tests cover it; the push that recorded this proof is the live example).
- The unnumbered `latest` release of the old pipeline was deleted by the first numbered release (`v0.2.46`); the step
  `gh release delete latest ... || true` in the workflow can be removed afterwards.
- If `main` moved so a branch is not a fast-forward, the run says so in its summary; look at what is on `main`, then merge by
  hand. Actions on Node 20 print a deprecation warning (GitHub forces Node 24); bump their versions when it becomes an error.

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
- Cloud sessions are bound to one `ccr-*` branch and must not push to `main` themselves: the pipeline merges good builds and
  releases them (see "Releases and the automatic merge"). Every run attaches the APK as a run artifact.
- Check CI with the GitHub MCP tools (`actions_list` with `workflow_runs_filter.branch`, `get_job_logs` with `failed_only`);
  `tools/ci.sh` does the same with `gh`, which worked read-only here but is not the sanctioned route.
- `javac` exists but there is no Android jar. `cd app/src/main/java && javac -proc:none -d <scratch> <files>` still finds
  syntax errors and misspelled app-internal names: ignore errors whose `symbol:` is an Android, org.json or Shizuku class.
- Offline tests: `tools/test.sh` (see "Remote screen control"; it also tests the release pipeline's merge gate). This sandbox has `ffmpeg` with libx264 and the `trace_headers`
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
AdvancedPanel, ScreenPrefs (settings), PhonePower (Sleep / Wake rules), VideoStream, AnnexBFramer (cuts the stream into pictures), SpsFix (decoder),
CtlChannel, AspectSurfaceView, PhoneScreenView.
Tools: `tools/checkpoint.sh` (log, commit, push with retries), `tools/ci.sh` (same, then watches the build with gh),
`tools/handoff-check.sh` (warns when this file is stale), `tools/test.sh` (offline tests, including
`tools/test/test_release_decision.py` for the release pipeline's merge gate, and `tools/test-framer.sh` for the picture cutter).
`CHECKPOINTS.md` is the append-only log they write.
