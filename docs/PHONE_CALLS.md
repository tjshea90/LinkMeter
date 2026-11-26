# Phone calls from the tablet (the Calls tab)

Read this first when you touch anything named `Call*`, `Headset*`, `Dial*` or the Calls tab. Notes of the session that built it:
`tools/progress.sh --show calls`. Nothing here has been run on a phone or a tablet yet (see "Not known until a device tries").

## The user's request (2026-11-16, verbatim)

"Just like the texting feature of this app, build a phone feature which allows me to control the phone app on the phone from my
tablet. Include an easy way for me to connect the phone to my Bluetooth headset. I want it to be modeled off of popular phone apps,
with a large, easy to use dial pad, contacts which are shared with the texting feature already in linkmeter, and easy connection to
my Bluetooth headset. Determine which is better, should my phone be connected to the Bluetooth or my tablet? The phone will be making
the calls, but my tablet is much closer. Both the tablet and Bluetooth headset will be connected to the phone by Bluetooth, and the
tablet with the additional wifi link."

## THE ANSWER TO "PHONE OR TABLET FOR THE HEADSET": THE PHONE

Connect the headset to the PHONE. The tablet is the remote control: it dials, answers, hangs up and shows the call, but the voice never
passes through it.

Why (the first reason alone decides it):

1. **The call's sound exists only inside the phone**, between its modem and its audio chip. Android gives no app a way to take the other
   person's voice out of a cellular call (the capture sources `VOICE_CALL` / `VOICE_DOWNLINK` need `CAPTURE_AUDIO_OUTPUT`, which only
   the system holds) and no way to put anything into the call's microphone side. A headset on the tablet would need exactly that. There
   is also no Android setting that lets a tablet act as the "hands-free" side of another phone's call to a third device (the tablet's
   Bluetooth stack has no hands-free *gateway* for a call that is not its own).
2. **The phone and a headset already speak the right protocol.** The hands-free profile (HFP) carries a call's voice in both directions
   with the phone's own echo cancelling and noise reduction, and starts and ends by itself with the call. Through the tablet it would be
   two Bluetooth hops plus Wi-Fi in the middle: delay, dropouts, and a battery bill on both devices.
3. **The call survives the tablet.** A call on a headset connected to the phone goes on if the tablet sleeps, runs out of battery,
   closes the app or walks out of Wi-Fi range. The headset's own buttons answer and end calls exactly as with any phone.
4. **Phones do this at the same time as the tablet link.** The phone keeps the tablet's small data link (the RFCOMM link of
   `docs/REMOTE_LINK.md`) and the headset's audio link together; Bluetooth is built for several links at once. The tablet's Wi-Fi link
   to the phone's hotspot is a different radio use and is untouched.

The one real cost is the thing the user noticed: "the tablet is much closer". The headset must be within Bluetooth range of the PHONE
(about 10 m in the open, often 3 to 6 m through walls and a body). The Calls tab says when the headset is connected, shows how many
times it dropped since the app started, and the user guide tells the user to keep the phone in the room they talk in. If the phone has
to sit far away (a window for signal), a headset with Bluetooth "multipoint" can stay connected to the tablet for music and to the phone
for calls; the tablet just cannot carry the call.

## What the app does and what Android does not let it do

DOES (all on the phone, all behind the switch "Let the tablet make and answer calls", off until the user switches it on):
- places a call to a number the user typed or chose (`TelecomManager.placeCall`, permission `CALL_PHONE`); the phone's own Phone app
  then runs the call and routes the sound to the headset;
- answers a ringing call (`acceptRingingCall`) and ends or declines one (`endCall`), permission `ANSWER_PHONE_CALLS`;
- tells the tablet when the phone rings, dials, is on a call or is idle, with the number when Android hands it over (needs
  `READ_PHONE_STATE`, `READ_CALL_LOG`);
- reads the call log (recents, missed) and the contacts (the same reader the Texts tab uses);
- mutes the microphone and moves the sound between the phone's ear speaker, the loudspeaker and the headset (best effort: each
  is read back, and the tablet says plainly when the phone did not take it);
- connects the phone to a paired headset, disconnects it, finds and pairs a new one (Bluetooth permissions; the privileged connect goes
  through `ShellMain bt-connect` as the shell user).

DOES NOT (said on the screens too, never faked):
- **Emergency numbers** (911, 112, ...). Android sends a third-party app's request for an emergency number to the phone's own
  dialer instead of calling, and a remote control that might not connect in an emergency is worse than none. The tablet refuses and
  says to use the phone itself.
- **Service and USSD codes** (anything that starts with `#` or ends with `#`, e.g. `#225#`, `*#06#`): decision of 2026-11-10 (this phone
  answered "invalid MMI" to all of them). Numbers that only start with `*` (`*67`, `*86`) are ordinary calls and work.
- **Tones during a call** (pressing 1 for the menu of a company): Android gives a call's tones only to the app that IS the phone app.
  The in-call screen has "Phone screen", which opens the Screen tab so the phone's own in-call keypad can be used.
- Hold, merge, add a call, conference, video, call recording, voicemail list, spam filter: the phone's own app does those.
- Notify the tablet of a call when LinkMeter is not in front: NOT by default (a tablet service costs battery; same decision as for texts). Since 2026-11-19 the FLOATING PHONE (`docs/FLOATING_PHONE.md`) is exactly that service, on by default but only once calls work and Android's "Display over other apps" is given, and off with one switch (Settings > Floating phone).

## Design

### Protocol (phone side `CallApi`, pure Java, tested over a real socket like `SmsApi`)

Every route is `/call/...`, answered 403 `off` when the switch is off, except `/call/state`. An older phone app answers 404 "unknown
request" (the tablet then says to update the phone). `VERSION = 1`.

| Route | What |
|---|---|
| `GET /call/state` | version, switch, which permissions are missing (words), the phone's note |
| `POST /call/grant` | the phone gives itself the permissions through Shizuku (like `/sms/grant`) |
| `GET /call/now?since=&wait=` | the live picture (calls, mute, route, headset line, missed count) and a stamp; waits up to 25 s for news |
| `POST /call/dial` `{number}` | guarded by `CallRules.check`; one call at a time |
| `POST /call/answer`, `POST /call/hangup` | answer the ringing call; end or decline |
| `POST /call/mute?on=1|0`, `POST /call/route?to=phone|speaker|headset` | read back, answer says what the phone really did |
| `POST /call/volume?stream=media|call&by=-3..3` | the headset's volume, see "Headset volume" below; the levels themselves ride in `/call/now` |
| `GET /call/log?limit=&before=` | recents with names, type, time, duration, whether new |
| `POST /call/log/seen` | marks missed calls as seen (the badge) |
| `GET /call/contacts?q=&limit=` | the phone's contacts (same reader as `/sms/contacts`) |
| `POST /call/contact` | saves or renames a contact (same writer as `/sms/contact`) |
| `GET /call/headset` | paired audio devices, which are connected, the chosen one, whether Connect can work |
| `POST /call/headset/choose?addr=` `connect?addr=` `disconnect` | |
| `POST /call/headset/scan`, `GET /call/headset/found`, `POST /call/headset/pair?addr=` | find and pair a new headset |
| `POST /call/headset/settings` | opens the phone's Bluetooth settings screen (the always-works fallback; then use the Screen tab) |

The tablet reaches these over the hotspot or the Bluetooth link exactly like `/sms/*` (`Reach`); `/call/now` is a long poll like
`/sms/stamp`, so a ring shows on the tablet within about a second and nothing polls while the app is in the background (unless the floating phone, `docs/FLOATING_PHONE.md`, is on: it holds the same watch while its service runs).

### Headset volume (2026-11-21)

The user wrote (verbatim): "Add volume options to the app so I can change the volume of my bluetooth from my tablet when it is connected to the
phone . Put the volume settings in both the app on the phone tab and on the accessibility phone floating widget".

- WHAT IT MOVES: Android's two streams that reach a headset on the phone. **Media** (`STREAM_MUSIC`: what plays when no call is on; with a
  Bluetooth headset connected Android sends the level to it, "absolute volume") and **Call** (`STREAM_VOICE_CALL`: the voice of a call). Ranges are
  Android's own steps (a headset has about 0..15 media and 1..5 call), not percent.
- WHERE IT SHOWS: the tablet's **Phone tab > "Headset volume"** (two rows, Media and Call, each with a quieter button, rising bars, a louder button and
  "7 of 15"; `ControlTab`, reads the same live picture as the Calls tab) and the **floating phone** (`MiniPhoneView`, one compact row under the
  buttons, shown while the headset is on the phone; a chip says Media or Call and switches it; it starts on Call during a call and on Media otherwise).
- ONE PATH FOR BOTH: the live picture `Calls.Now.volume` (flat keys `vv vm vmlo vmhi vmh vc vclo vchi vch` in `/call/now`; `vv` missing = an old phone
  app, said as "too old for volume") and ONE route `POST /call/volume?stream=&by=`. The phone listens to Android's `VOLUME_CHANGED_ACTION`, so
  the phone's own keys and the headset's buttons show on the tablet within a second. Hotspot only, and behind the phone's switch "Let the tablet make and
  answer calls", like the rest of `/call/*` (the Phone tab says so when it is off).
- THE PROMISES (all in the pure `VolumeRules`, `tools/test/VolumeRulesTest.java`, 59k checks, and again on the phone in `CallBackend.volume`): (1) it
  only ever means the HEADSET: no headset connected, media that does not go to a Bluetooth device, or a call whose sound is on the phone's ear or
  speaker, and NOTHING moves (the phone's own speaker can never get louder by a press here); the call volume moves only while a call is on and its
  sound is on the headset (or Android does not say where it goes while a headset is connected); (2) one press is one step: the buttons send +1 or -1, the
  phone refuses 0 and more than 3, so nothing jumps to full loudness in somebody's ear; two quick presses are two steps (the phone does them one after the other);
  (3) a change is believed only when the phone READS THE LEVEL BACK: Android may refuse to go louder (a safe-volume question on the phone's own screen), then
  the tablet says "Android did not make it louder (6 of 15). The phone may be asking on its own screen to allow louder sound: look at it."
- NEW MANIFEST PERMISSION: `MODIFY_AUDIO_SETTINGS` (a normal permission, granted at install, no question). Android ignores several audio requests
  without it (this app already asks for the microphone mute and the sound's route), so Mute and the audio button may start to work as well.
- Pure: `VolumeRules`, `Calls.Volume`/`Calls.Level`, `CallJson`. Phone: `HeadsetVolume` (reads the two streams, moves one, `getAudioDevicesForAttributes`
  tells where media goes), `CallBackend.volume` + the broadcast receiver. Tablet: `RemoteCalls.volume`, `CallWatch.volume`, `VolumeView` (a row),
  `VolumeBarView` (the bars), the card in `ControlTab`, the row in `MiniPhoneView` and `FloatingPhoneController.volumeStep/volumeSwitch`.

### Files

Pure (no Android; tests in `tools/test/CallsLogicTest.java`, `CallApiTest.java`): `Calls` (the model), `CallRules` (dial check,
emergency and code detection, number display, keypad letters, contact search, recents grouping, call timer words, banner rules),
`CallJson` (the wire form), `CallApi` (routes over a `Backend`), `HeadsetRules` (what the headset chip and the Connect button say and
do for every state).

Phone (Android): `CallBackend` (Telecom, audio, call log, contacts, permissions), `CallService` (the one set of objects, `serve`),
`CallWatcher` (phone state and the incoming number, bumps the `ChangeGate`), `CallAccess` (permissions and the Shizuku grant),
`HeadsetLink` (Bluetooth reading, pairing, the connect ladder), `ShellMain bt-connect`, a Calls card on the phone's own screen,
`Cfg.callsOn`.

Tablet (Android): `RemoteCalls` (client, pure Java over `RemoteStore.Link`), `CallsTab` (title, headset chip, Keypad | Recents |
Contacts), `DialPadView` (the large keypad), `InCallView`, `HeadsetPanel`, `CallWatch` + `CallBanner` (the strip at the top of every
tab while a call rings or is on, only while the app is in front), a Call button in a Texts conversation and Message buttons in the Calls
lists. Contacts are SHARED with Texts: the phone's contacts come from the same reader, and the tablet's own names are the same
`TextsLocal` names (rename in one place, see it in both).

### Safety (the tripwire `tools/test/test_dial_paths.py` was changed on purpose, 2026-11-16)

Before: "the app dials nothing". Now: a call is placed only by `CallBackend.dial`, which must call `CallRules.check` first (the test
reads the source and fails if `placeCall` appears anywhere else or without the check); `sendUssdRequest`, `ACTION_CALL`, `ACTION_DIAL`,
`CALL_PRIVILEGED`, `am broadcast`, secret codes, typed digits through the Screen tab and `input keyevent` for digits, `*`, `#`, call
keys stay forbidden. Nothing calls by itself: a call needs a press on the tablet, the tablet must be paired, and the phone's switch
must be on.

## A call that stayed on the tablet after it ended (2026-11-26)

The user wrote (verbatim): "sometimes the phone tab is unresponsive, especially if I switch apps, make a phone call, then return to the app, and it doesn't refresh and know that I already ended the call and hung up."
Found by reading the code (nothing seen on a device), three causes, all changed:
1. PHONE: `CallTracker` learned that a call ended only from Android's PHONE_STATE broadcast, and `CallBackend.dial` refuses a new call while the tracker holds one ("A call is already on"). A lost or late broadcast kept an ended
   call for ever. NOW `CallTracker.reconcile` (pure, `CallsLogicTest`: 50,000 random steps) brings the tracker to what `TelephonyManager.getCallState()` says; `CallBackend.calls()` does it before every look and before dial, answer,
   hang up, mute and route, and a watchdog does it every 3 s while a call is on or ringing (nothing runs otherwise). Needs the phone-state permission the app already asks for.
2. TABLET: the watch holds one request open at the phone (it waits for news). When the connection dies silently (the tablet slept, the Wi-Fi changed) that request holds the loop for up to 32 s, and `wake()` could not end it.
   NOW `CallWatch.refresh()` (the app comes back, the screen comes on) drops it and looks at once without waiting for news (at most once per 1.5 s); `stop()` forgets the stamp, so the next listener starts with a full look; an old
   loop can no longer park the new one; `MainActivity.onResume` redraws the call strip and `CallsTab.onShow` redraws from the watch.
3. TABLET, Phone tab: `ControlTab.refreshStatus` never asked again while one question was out, and a question that never came back (a Bluetooth connect that hung) stopped it for ever. NOW a question that is 12 s old is
   not waited for, the newest answer wins (`askSeq`), and showing the tab again always asks afresh.

## Status

- [x] Pure logic + tests (`Calls`, `CallRules`, `CallTracker`, `CallJson`, `CallApi`, `RemoteCalls`, `HeadsetRules`; `CallsLogicTest` about 1.17 million checks, `CallApiTest` 91 checks over a real socket)
- [x] Phone side (`CallAccess`, `CallBackend`, `CallService`, `HeadsetLink`, `ShellMain bt-connect|bt-disconnect`, `Menus.bluetoothSettings`, the Calls card on the phone's own screen, manifest, `Api` route; the release build compiles)
- [x] Tablet side (`CallWatch`, `CallBanner`, `CallsTab`, `DialPad`, `InCallView`, `HeadsetPanel`, `CallsViews`, the Call button in Texts, the Message buttons in Calls, the shared names, a warning on Hotspot off / Standby while a call is on)
- [x] Tripwire rewritten (`tools/test/test_dial_paths.py`; `python3 tools/mutate_dial.py`: 48 breakages, all caught), `docs/SAFETY.md` (Dialing row, section 12), `docs/USER_GUIDE.md`, `docs/REMOTE_LINK.md`
- [x] Screen pictures looked at (`tools/preview.sh --tests "com.tj.linkmeter.CallsPreviewTest"`, 21 scenarios; the pictures are `tools/uipreview/out/calls-*.png`)
- [ ] Deliberate breakages of the screens' glue (`tools/mutate_calls_glue.py`, run it from a git worktree): see HANDOFF for the result
- [ ] Release: see HANDOFF ("RELEASED AS")
- NOT BUILT: calls over the Bluetooth-only link, tones during a call, hold/merge/conference, an audio-route fallback through the shell. (A tablet service for calls while the app is in the background IS built since 2026-11-19: the floating phone, `docs/FLOATING_PHONE.md`.)

## Not known until a device tries (say so to the user)

- Headset volume (2026-11-21): whether this Samsung (One UI on Android 16) takes `setStreamVolume` from LinkMeter for the headset and sends it on to
  it (the answer is read back; if the level does not move, the tablet says so); whether the call volume moves while a call is on the headset and whether
  Android reports the call's route there (else the row trusts a connected headset); whether Android asks on the phone's screen before allowing a louder
  media volume in the headset (then the tablet says "look at the phone"); how the headset's own buttons show on the tablet (the broadcast).

- Whether the shell user may connect a headset on this phone (`ShellMain bt-connect` calls the hidden `BluetoothDevice.connect()`);
  if not, Connect says so and the fallbacks are: turn the headset off and on (most reconnect by themselves), or "Open the phone's
  Bluetooth settings" and use the Screen tab.
- Whether a non-phone app may move a call between ear, speaker and headset on One UI 8 (`setSpeakerphoneOn` /
  `setCommunicationDevice`); the tablet reads the route back and says when it did not change.
- Whether Android hands the incoming number to LinkMeter on One UI 8 (`PHONE_STATE` with `READ_CALL_LOG`); without it a ringing call
  shows as "Incoming call" and the number appears in Recents afterwards.
- Whether Samsung's Phone app brings its own in-call screen up on the phone when the tablet dials (it probably does, over the lock
  screen); the call itself does not depend on it.
- Whether the headset keeps its link with the Bluetooth RFCOMM link and a 2.4 GHz hotspot running at the same time (expected, since
  Bluetooth and Wi-Fi share the antenna and Samsung times them, but a hotspot on 5 GHz is kinder to the headset).
