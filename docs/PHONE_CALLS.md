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
- Notify the tablet of a call when LinkMeter is not in front (a tablet service for that costs battery; same decision as for texts).

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
| `GET /call/log?limit=&before=` | recents with names, type, time, duration, whether new |
| `POST /call/log/seen` | marks missed calls as seen (the badge) |
| `GET /call/contacts?q=&limit=` | the phone's contacts (same reader as `/sms/contacts`) |
| `POST /call/contact` | saves or renames a contact (same writer as `/sms/contact`) |
| `GET /call/headset` | paired audio devices, which are connected, the chosen one, whether Connect can work |
| `POST /call/headset/choose?addr=` `connect?addr=` `disconnect` | |
| `POST /call/headset/scan`, `GET /call/headset/found`, `POST /call/headset/pair?addr=` | find and pair a new headset |
| `POST /call/headset/settings` | opens the phone's Bluetooth settings screen (the always-works fallback; then use the Screen tab) |

The tablet reaches these over the hotspot or the Bluetooth link exactly like `/sms/*` (`Reach`); `/call/now` is a long poll like
`/sms/stamp`, so a ring shows on the tablet within about a second and nothing polls while the app is in the background.

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

## Status

- [x] Pure logic + tests (`Calls`, `CallRules`, `CallTracker`, `CallJson`, `CallApi`, `RemoteCalls`, `HeadsetRules`; `CallsLogicTest` about 1.17 million checks, `CallApiTest` 91 checks over a real socket)
- [x] Phone side (`CallAccess`, `CallBackend`, `CallService`, `HeadsetLink`, `ShellMain bt-connect|bt-disconnect`, `Menus.bluetoothSettings`, the Calls card on the phone's own screen, manifest, `Api` route; the release build compiles)
- [x] Tablet side (`CallWatch`, `CallBanner`, `CallsTab`, `DialPad`, `InCallView`, `HeadsetPanel`, `CallsViews`, the Call button in Texts, the Message buttons in Calls, the shared names, a warning on Hotspot off / Standby while a call is on)
- [x] Tripwire rewritten (`tools/test/test_dial_paths.py`; `python3 tools/mutate_dial.py`: 48 breakages, all caught), `docs/SAFETY.md` (Dialing row, section 12), `docs/USER_GUIDE.md`, `docs/REMOTE_LINK.md`
- [x] Screen pictures looked at (`tools/preview.sh --tests "com.tj.linkmeter.CallsPreviewTest"`, 21 scenarios; the pictures are `tools/uipreview/out/calls-*.png`)
- [ ] Deliberate breakages of the screens' glue (`tools/mutate_calls_glue.py`, run it from a git worktree): see HANDOFF for the result
- [x] Released as v0.2.188 (https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.188)
- NOT BUILT: calls over the Bluetooth-only link, tones during a call, hold/merge/conference, a tablet service for calls while the app is in the background, an audio-route fallback through the shell.

## Not known until a device tries (say so to the user)

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
