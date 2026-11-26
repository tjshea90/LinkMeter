# The floating phone, the headset's battery and the headset on the tablet

Read this first when you touch `Float*`, `MiniPhoneView`, `BatteryIconView`, `BatteryRules`, `BtBattery`, `TabletHeadset*` or the
battery lines of the Calls screens. Notes of the session that built it: `tools/progress.sh --show floating-phone`. **Nothing here has
been run on the phone or the tablet yet** (see "Not known until a device tries"). It builds on `docs/PHONE_CALLS.md`: the headset
belongs to the PHONE, the tablet is the remote control.

## The user's request (2026-11-19, verbatim)

"1) build a small floating widget that automatically pops up on my tablet when I'm using the phone feature which shows normal phone
information such as length of call, a dial pad i can open and close (like a mini phone), and a green call button and red hang up
button. Make the gui cool and the widget small and floating but it can open up a dial pad and make a mini phone that i can move around
the screen. Include a bluetooth on and off button to connect or disconnect my bluetooth headset from the phone. Include a bluetooth
battery monitor that shows battery percentage in a cool battery icon.
2) include a simple way for me to view my bluetooth headset battery in the linkmeter app when it is connected to the phone.
3) include a way for me to toggle on and off bluetooth link to my tablet so i can use the bluetooth headset with my tablet as well as
my phone"

## What was built

### 1. The floating phone (tablet)

A small dark glass card (252 dp wide) drawn OVER EVERY APP by `FloatingPhoneService` (Android's "Display over other apps",
`TYPE_APPLICATION_OVERLAY`). It pops up by itself when the phone rings, dials or a call is on, and stays about 6 seconds after the call
so the length can be read ("Call ended · 3:12"). It shows:

- who and how long (`CallRules.status`, counted from the phone's clock like the Calls tab), a status dot (amber ringing, green on a call);
- the headset's battery in a glowing battery icon (`BatteryIconView`: five lamp ticks, a gradient fill that glides, colour by band,
  a slow pulse when critical, a dash when the headset does not say);
- a green button (Answer while it rings, Call when digits are typed, otherwise it opens the pad), a red button (Decline / End);
- two Bluetooth buttons: the headset's link to the PHONE (connect / disconnect, through `/call/headset/*`) and to THIS TABLET
  (`TabletHeadset`), lit blue while connected;
- an arrow that opens the DIAL PAD (twelve compact keys, a long press on 0 types `+`, backspace, a long press clears) and a cross that
  closes the card when no call is on.
- Drag the top strip to move it anywhere; on release it slides to the nearer side edge and the place is remembered (`Cfg.floatX/Y`).

It is only a remote control. The call is placed, answered and ended by the PHONE through the same `/call/*` routes as the Calls tab
(`CallWatch.dial/answer/hangup`), so the same guard refuses emergency numbers and service codes: the tablet checks `CallRules.check` first
(says why on the card), the phone checks again (`CallApi.dial` → `CallBackend.dial`). "Ask before calling without the headset" (the Calls
tab's setting) shows as a warning on the card: the first press says the headset is not connected, a second press within 7 s calls on the
phone. The call's sound stays on the headset that is connected to the phone.

When it shows (`FloatRules.visible`, tested for every combination): never without Android's permission; a call shows it when the automatic
pop-up is on (Settings > Floating phone); "Show the phone now" (Settings, Calls tab's handset button, the notification's "Show phone")
keeps it until the cross is pressed; an open pad keeps it; the 6 s after a call.

What it costs: `FloatingPhone.wanted` = tablet role AND the setting on (default ON) AND calls work (`Cfg.callsWatch`, set once the Calls tab
found the phone allowing calls) AND Android's permission given. Otherwise NO service runs. While it runs it holds the ONE `CallWatch` of
the process (shared with the app's screens, counted: `start()`/`stop()`), which is a long request to the phone that the phone answers
only on news or after 20 s; a screen-on receiver asks the phone at once when the tablet wakes. A foreground-service notification (minimal
importance) with "Show phone" and "Turn off" is what Android requires. After a restart the app's `BootReceiver` brings it back.

#### Android 10 Go (the user's tablet): the Accessibility way

The user's tablet is an Android 10 **Go** device. Android turns "Display over other apps" off there for every app: the screen the Allow button
opens says "Feature not available: This feature has been turned off because it slows down your phone" (the user's screenshot, 2026-11-19), so
the overlay permission can never be given and `FloatingPhoneService` can not draw anything. The same card is therefore also hosted by
`FloatingPhoneAccess`, an ACCESSIBILITY service: an enabled accessibility service may add windows of type `TYPE_ACCESSIBILITY_OVERLAY` with no
overlay permission. All the logic lives in `FloatingPhoneController` (window, drag, actions, hand-over, hiding); the two hosts only start it
(`FloatingPhoneService` with a foreground notification and `TYPE_APPLICATION_OVERLAY`; `FloatingPhoneAccess` as the system keeps it, no
notification). Only one runs: when the accessibility service is connected `FloatingPhone.wanted` is false and the foreground service is stopped.
The user switches it on once: Settings > Floating phone > **Turn on with Accessibility** (opens Android's Accessibility settings) > "LinkMeter
floating phone" > on. **It asks for nothing else:** `res/xml/floating_phone_access.xml` declares no window content (`canRetrieveWindowContent`
false) and no event types, `onAccessibilityEvent` does nothing, and the Settings card and the service's own description say so. On a device
that allows overlays the Allow button is shown instead (`FloatingPhone.overlayUnavailable` = `ActivityManager.isLowRamDevice()` decides which).
NOT KNOWN until the tablet tries: whether Android 10 Go lets this service draw (expected: the restriction is on the overlay app-op, not on
accessibility windows), whether it survives the tablet's memory pressure, and how the Accessibility switch's own warning reads.

### 2. The headset's battery in the app
 (tablet) and how the phone reads it

- Calls tab: the battery icon beside the headset chip while the headset is connected to the phone; tap = the Headset screen.
- Headset screen: a "Headset battery" card at the top (big icon, percent, words from `BatteryRules.words`).
- Home: a "Headset" line in the Switches card ("Sony WH-1000XM4 · 78%").
- The wire: `Calls.Device.battery` (`"bat"`) and `Calls.Now.headsetBattery` (`"hsb"`, in the live picture, so a change reaches the tablet through
  the long request within a second). `-1` = the headset does not report, `-2` = the phone's app is too old to say (the field is missing).
- The phone (`HeadsetLink.battery`) tries three ways and believes only 0 to 100 (`BtBattery.clean`): (a) the hidden
  `BluetoothDevice.getBatteryLevel()` in its own process (dropped for good when Android refuses hidden calls), (b) Android's
  `BATTERY_LEVEL_CHANGED` broadcast, (c) the same hidden call as the SHELL user (`ShellMain bt-battery ADDR`, at most every 3 minutes
  when it gave a number, every 5 when it did not, only with Shizuku, on a background thread, never inside a request), which falls back to
  the device's battery line in `dumpsys bluetooth_manager` (`BtBattery.fromDump`, tolerant: another device's line is never used).

### 3. The headset on THIS TABLET too (toggle)

Settings are in the Headset screen (Calls tab > chip): a card "This tablet" (`TabletHeadsetRules` decides the words and buttons) and the
second Bluetooth button of the floating phone. The tablet's Bluetooth is read from its own profile list (`TabletHeadset`, only while a
screen listens). Connecting and disconnecting go through a ladder, each rung READ BACK (`TabletHeadset.set`):

1. it is already so; 2. Shizuku on the tablet (`ShellMain bt-connect|bt-disconnect`, only if Shizuku runs there and LinkMeter is allowed);
3. the hidden profile `connect`/`disconnect` of the hands-free and music profiles from this app (Android usually refuses: it wants a
privilege); 4. Android's own Bluetooth list opened for ONE tap, and said as what it is ("tap the headset there"; `Way.ASKED`, never
claimed as done). An ordinary app on Android 10 cannot connect a headset; do not "fix" that by claiming success.

Which headset: the one chosen on the tablet, else the phone's (the same device has the same address on both), else the same name, else the
only audio device paired with the tablet (`TabletHeadsetRules.pick`). Not paired with the tablet yet: the card has "Pair with this tablet"
(opens Android's Bluetooth list: pairing needs the headset in pairing mode).

**ONE CONNECTION AT A TIME (the user's rule, 2026-11-19: "when I connect bluetooth to my tablet it must disconnect from my phone first, and
vice versa").** Connecting the headset to the tablet first asks the PHONE to let go (`/call/headset/disconnect`), waits 1.5 s, then connects
the tablet; connecting it to the phone first lets go of the TABLET (the same ladder, read back), then asks the phone to connect. Pure plan:
`HandOverRules.plan` (tested for every combination: the other side always lets go BEFORE the connect); the carrying out, used by the Headset
screen, the floating phone's two buttons and "Connect and call": `HeadsetHandOver.toTablet` / `toPhone`. Stops and says why: a call is on and the
headset is on the phone (its sound would be lost: "End the call first"); the phone answers "no" to letting go; the tablet's disconnect was only
ASKED (Android's list opened for a tap: the phone is NOT connected, the words say "disconnect it in the list, then press the Phone button
again"). A phone that cannot be reached does not block the tablet (the headset may not be on it): the connect goes on and the words say the
phone was not asked. A headset that CAN keep two connections ("multipoint") has the switch **My headset can stay connected to both** (This tablet
card, `Cfg.headsetBoth`, off by default): then nothing is disconnected. Pressing a Bluetooth button of the side that already has the headset
only disconnects it there. A call's sound is never moved to the tablet (impossible, see `docs/PHONE_CALLS.md`).

### 4. The headset's volume (2026-11-21; the user: "Add volume options ... on the phone tab and on the accessibility phone floating widget")

A row at the bottom of the card, shown while the headset is connected to the PHONE and the phone's app knows about volume: a chip ("Media" or
"Call"), a quieter button, rising bars, a louder button. The chip switches the row between the media and the call volume; it starts on Call while a
call is on and on Media otherwise, and goes back to that when a call begins or ends. A press is one step on the phone, read back. A dimmed button still
answers: it puts the reason in the card's message line ("The call volume can be changed while a call is on."). Same rules, same route and the same picture
as the Phone tab's card: `docs/PHONE_CALLS.md`, "Headset volume". Code: `MiniPhoneView.buildVolume`, `FloatingPhoneController` (`buildModel`,
`volumeStep`, `volumeSwitch`). The accessibility host (`FloatingPhoneAccess`) draws the same view, so it has the row too.

## Recents, Contacts and the small texting card (2026-11-26)

The user wrote (verbatim): "for the phone floating widget, add a text button which opens a mini texting floating widget just like the phone one." and "add recents and contacts to the floating phone widget".

- **The card** (`MiniPhoneView`, 264 dp wide now, five round buttons in its row: phone Bluetooth, green, red, **Text**, tablet Bluetooth; the two Bluetooth labels are one word, their state is the colour). The arrow opens a strip of
  three tabs: **Keypad** (as before), **Recents** (`RemoteCalls.log`, grouped like the Calls tab: `MiniLists.recents`) and **Contacts** (`RemoteCalls.contacts`, letters, one line per person, a search box:
  `MiniLists.contacts`). A line: tap = its number goes onto the keypad (the green button then calls it, same guard, same headset warning); its green button = the same and presses the green button; its chat bubble = opens the
  texting card at that person. The lists are asked for when the tab opens and kept (calls 30 s, contacts 5 min; a call that ends makes the call list ask again); `FloatingPhoneController.needRecents/needContacts` via `CallWatch.fetch`.
- **The small texting card** (`MiniTextView` + `MiniTexts` + `MiniTextRules`, its own window beside the phone card, draggable, its place is remembered: `Cfg.floatTextX/Y`). Screens: the list of conversations (unread dot, newest line,
  time), one conversation (the newest 30 messages as bubbles, a box, Send, a call button that hands the number to the phone card's keypad), and New message (a To box: a number or a name; the contacts it offers:
  `MiniTextRules.suggestions`). It asks the phone through the SAME `RemoteSms` routes as the Texts tab (`TextsTab.remoteFor`), listens for news (the waiting stamp request) only while it is up, marks a conversation
  read when it is opened, and puts a text in the conversation at once as "Sending…" until the phone's copy takes its place (`MiniTextRules.merge`; a failed one stays red with the reason). It hides while LinkMeter's own screens
  are in front and is back when they go (`FloatRules.textVisible`). Not built: pictures (a picture message shows as [Picture]), search over all messages, groups of more than the list shows, a badge on the Text button.
- **The keyboard** (`FloatKeyboard`): a floating window never takes the keyboard (`FLAG_NOT_FOCUSABLE`), so a box inside it could not be typed in. The first touch of a box asks the controller (`keyboardFor`) to clear that flag
  and to listen for touches outside; the box takes the focus 180 ms later and Android's keyboard (the user's own: glide typing works) comes up; the card is moved to the top of the screen meanwhile so the keyboard cannot cover it.
  A touch outside, Back and the Done key give the focus up and put the flag and the place back. NOT KNOWN UNTIL THE TABLET TRIES: whether Android 10 Go lets an ACCESSIBILITY overlay take the keyboard this way (the
  foreground-service way, `TYPE_APPLICATION_OVERLAY`, does). If it does not, the boxes cannot be typed in there and the Texts tab is the way; say so to the user.
- **Tests**: `tools/test.sh` (`FloatingLogicTest`, 55,461 checks incl. `MiniLists`, `MiniTextRules`, `FloatRules.textVisible`); `tools/preview.sh --tests "com.tj.linkmeter.FloatingExtrasPreviewTest"` (7 scenarios: the tabs, a call from a
  line, the keyboard flags, the texting card end to end against the pretend phone, a new message, stepping aside for the app, and the two stale-call tests). WRITTEN, COMPILED, NOT RUN: Maven Central rate-limited the sandbox's shared address
  (`retry-after` about 20 hours) and the one-off way round it (Google's mirror of Central through a Gradle init script) was refused by the sandbox's permission check. Run them first when a session can.

## Files

Pure (no Android; `tools/test/FloatingLogicTest.java`, 55k checks, run by `tools/test.sh`): `BtBattery`, `BatteryRules`, `FloatRules`
(visibility, buttons, words, where the card may sit), `TabletHeadsetRules`, `HandOverRules` (one connection at a time); the wire fields in `Calls`/`CallJson` (`CallsLogicTest`,
`CallApiTest`).

Android: `HeadsetLink` (phone: battery ways), `ShellMain` (`bt-battery`), `CallBackend.now`, `TabletHeadset` (tablet Bluetooth),
`HeadsetPanel` (battery card + tablet card), `CallsTab` (battery by the chip, the handset button), `HomeTab` (Headset line),
`HeadsetHandOver` (moves the headset between phone and tablet), `BatteryIconView`, `MiniPhoneView` (the card), `FloatingPhoneService` (window, drag/snap, actions, notification), `FloatingPhone`
(switchboard, permission), `SettingsTab` (card "Floating phone"), `CallWatch` (now ONE shared, counted watch + headset actions),
`MainActivity` (holds the watch while in front, calls `FloatingPhone.sync`), `BootReceiver`, `Cfg` (`floatOn`, `floatX/Y`,
`tabletHeadsetAddr`), manifest (`SYSTEM_ALERT_WINDOW`, the service).

Pictures and behaviour against a pretend phone running the REAL phone-side code: `tools/preview.sh --tests "com.tj.linkmeter.FloatingPreviewTest"`
(15 scenarios: pictures `tools/uipreview/out/float-*.png`; the service is run end to end: a ring pops the card up, Answer / End / Call / the
two Bluetooth buttons reach the pretend phone, an emergency number is refused, the warning before a call without the headset, drag, snap and
the remembered place, the card steps aside for LinkMeter, a phone that cannot be heard mid-call is not "call ended"). Deliberate breakages of
the pure rules: `python3 tools/mutate_floating.py` (44, all caught; about two minutes). The tablet's pretend Bluetooth is
`TabletHeadset.pretend` (tests only). TEST ISOLATION: the watch is ONE object in the process, so every screen test starts with
`CallWatch.dropForTest()` (done by `PreviewTest.tablet()`); without it an earlier test's screens stay subscribed and ask the next test's pretend
phone things (the Calls tests then fail at random).

## Not known until a device tries (say so to the user)

- Whether this Samsung's headset reports a battery level to the phone at all, and which of the three ways reads it (the Headset screen and
  the card show a dash and say "does not tell the phone its battery level" when none does; nothing is invented).
- Whether Android 16 lets the shell user call the hidden `getBatteryLevel()` or shows the level in `dumpsys bluetooth_manager` (the parser
  was written from the format of other Android versions, from memory of them: it is deliberately tolerant and tested only on made-up dumps).
- Whether the overlay really shows over every app on the tablet (Android 10 Go) and drags smoothly on that slow tablet; whether the
  tablet's Wi-Fi stays up in sleep long enough for a ring to pop the card up (the tablet in Doze cannot hear the phone: the card appears
  when it wakes, the screen-on receiver asks at once).
- Whether the headset can be connected or disconnected on the tablet without a tap (needs Shizuku on the TABLET, probably not there), and
  whether Android 10's Bluetooth list opens from the floating phone while another app is in front (apps with the overlay permission are
  exempt from the background-start limit; expected, not seen).
- Whether the user's headset keeps two connections (multipoint).

## Status

- [x] Phone side: battery ways, shell command, wire fields, `CallApiTest`/`CallsLogicTest` extended
- [x] Pure rules and tests (`FloatingLogicTest`)
- [x] Tablet: Headset screen cards, battery by the chip, Home line, tablet headset ladder
- [x] The floating phone (service, view, settings, permission, boot) — compiles; pictures: see the preview run
- [x] Release: v0.2.194 (https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.194), published by the pipeline after a push to `ccr-floating-phone`
