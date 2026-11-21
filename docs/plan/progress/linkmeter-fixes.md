# LinkMeter session 2026-11-21 (branch claude/linkmeter-keyboard-launcher-fixes-fsk78t) - notes

User's words (verbatim), LinkMeter part:
1) "the attached screenshot button that says  switch wifi  on doesn't do anything" (Home banner: "The hotspot is on, but the tablet's Wi-Fi is off" + button "Switch Wi-Fi on", HomeTab.switchWifiOn -> TabletWifi.set)
2) "sometimes when i press on the phone icon in the phone tab to open the floating phone widget or press the bluetooth button right next to it, the buttons don't respond and i have to press them multiple times. Make these buttons responsive and show when i press them by quickly darkening or accenting the buttons when i press them, just like my keyboard app accents the keys when pressed." (Calls tab header: IconButton HANDSET 34dp, battery icon, chip "Headset")
3) "it is awkward to text by typing in a phone number and then i have to press the next button then start typing a message. Change the gui and buttons to match popular texting apps" (Texts new conversation)
Launcher+LinkMeter (shared): CS1 battery saver on at pocket return; CS2 prepare power-menu patch cover. See Launcher TASKS.md CS1/CS2.

Plan / status:
- [ ] 1 Wi-Fi button: visible feedback IN the banner, panel -> Wi-Fi panel -> Wi-Fi settings fallback chain, detect "did not open" (activity pause), say so.
- [ ] 2 press feedback: global PressFeedback (MainActivity.dispatchTouchEvent, foreground tint, no animation: the Launcher turns animations off in Quickstep so ripples vanish), 48dp targets on Calls header, floating phone window too.
- [ ] 3 Texts compose: To: field + contact suggestions + message box on ONE screen (like Google Messages / iMessage).
- [ ] CS1/CS2 (LauncherBridge pocket job + Launcher side).
