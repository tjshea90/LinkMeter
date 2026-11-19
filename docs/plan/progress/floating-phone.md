# Floating phone widget + headset battery + tablet headset toggle (session branch claude/floating-phone-widget-bluetooth-5fyvkw)

STATUS: WORKING (started 2026-11-19). NOTHING IS RUN ON A DEVICE. Read docs/FLOATING_PHONE.md once it exists.

## The user's request (verbatim, 2026-11-19)
1) build a small floating widget that automatically pops up on my tablet when I'm using the phone feature which shows normal phone
information such as length of call, a dial pad i can open and close (like a mini phone), and a green call button and red hang up
button. Make the gui cool and the widget small and floating but it can open up a dial pad and make a mini phone that i can move around
the screen. Include a bluetooth on and off button to connect or disconnect my bluetooth headset from the phone. Include a bluetooth
battery monitor that shows battery percentage in a cool battery icon.
2) include a simple way for me to view my bluetooth headset battery in the linkmeter app when it is connected to the phone.
3) include a way for me to toggle on and off bluetooth link to my tablet so i can use the bluetooth headset with my tablet as well as my phone

## Plan (order = checkpoint order)
- [ ] S1 PHONE: headset battery. Calls.Device.battery (-1 not reported, -2 old phone), Calls.Now.headsetBattery; CallJson keys
      "bat" / "hsb"; HeadsetLink.battery (in-process getBatteryLevel reflection, hidden BATTERY_LEVEL_CHANGED broadcast, ShellMain
      bt-battery ADDR as shell user + dumpsys parse); CallBackend.now fills it; tests CallsLogicTest + CallApiTest.
- [ ] S2 PURE RULES (tools/test): BatteryRules (band/words), FloatRules (when the widget shows / hides, what its buttons do),
      TabletHeadsetRules (tablet toggle words + ladder result).
- [ ] S3 TABLET headset (#3): TabletHeadset (bonded lookup, state, connect/disconnect ladder: already / Shizuku on the tablet via
      ShellMain bt-connect / hidden reflection / Android Bluetooth settings for one tap), card in HeadsetPanel, chip + battery card
      (#2), Home line.
- [ ] S4 WIDGET (#1): CallWatch shared + reference counted; BatteryIconView; MiniPhoneView; FloatingPhoneService (foreground,
      overlay window, drag + snap, expand/collapse, pad, call/hang up, BT buttons); manifest SYSTEM_ALERT_WINDOW + service;
      Settings card + Calls-tab entry; BootReceiver restart; Robolectric pictures (tools/preview.sh).
- [ ] S5 docs (docs/FLOATING_PHONE.md, USER_GUIDE, HANDOFF block), tools/test.sh, ./gradlew --no-daemon assembleRelease,
      python3 tools/mutate_dial.py (dial tripwire), push; manual build run (branch is claude/*: pipeline does not start itself).

## Facts found (do not rediscover)
- Branch is claude/*: a push starts NO GitHub run. Options: manual run of build.yml via actions_run_trigger (builds + tests only), or
  ask the user before pushing to a ccr-* branch (HANDOFF 2026-11-14 block).
- Tablet = Android 10 (API 29), app targetSdk 34, no Shizuku known on the tablet. A normal app cannot connect/disconnect a headset
  profile (BLUETOOTH_PRIVILEGED): ladder with read-back, last rung = open Android's Bluetooth settings (one tap), like TabletWifi.
- Existing: CallWatch (activity-bound long poll /call/now), CallBanner, CallsTab, HeadsetPanel, HeadsetRules, HeadsetLink (phone).
- The headset belongs to the PHONE (docs/PHONE_CALLS.md): calls stay there; the widget only controls.
