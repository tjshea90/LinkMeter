# Wi-Fi panel did not show after Hotspot on (the user's report, 2026-11-12) — progress notes

STATUS: WORKING (branch ccr-6685b0bb-kms4dg; main = c36055e = v0.2.170 at the start)

THE USER'S WORDS: "When I pressed the turn phone hotspot on button, then turn tablet WiFi on the tablet button, the WiFi setting didn't show up.
But before it was showing up"

FOUND BY READING THE CODE (not seen on a device):
- v0.2.168 (Wi-Fi follows the hotspot): Hotspot on over Bluetooth = the phone acts, then answers ok -> settleWifi -> Android's Wi-Fi panel within seconds.
- v0.2.169 (deep sleep, default "Turn it off"): Hotspot on to a phone in AIRPLANE MODE is answered "Sent" first (RemoteRoutes.cutsLink 4-arg:
  airplaneOn), so HomeTab follows up (FollowUp.GIVE_UP_FROM_AIRPLANE_MS 90 s) and opens the panel only when a status shows hs=true. The tablet's
  Wi-Fi is off, so that status can only come over Bluetooth, which the phone's airplane-mode switch disturbs. Gave up = no panel, ever.
- During the follow-up busy=true: the card's "Switch Wi-Fi on" is greyed and switchWifiOn() returns silently (it was meant for an in-flight CALL).
- Phone not reached + tablet Wi-Fi off: the Trouble card (NO_ROUTE/NO_ANSWER) offers only "Ask again": no Wi-Fi button.
- Wi-Fi on but not joined: the card says "open the tablet's Wi-Fi settings" and has no button for it.
PLAN:
1. pure TabletWifiRules.atSent(wish, wifiOn): ON may be done at the phone's "Sent" (request delivered, nothing in flight); OFF still only when confirmed.
2. HomeTab: at "Sent" for Hotspot on, open the panel at once; keep the follow-up across the pause that LinkMeter's own panel causes
   (keepFollowUntil), it goes on when the screen is back.
3. switchWifiOn works during a follow-up (only an in-flight call blocks it); a press clears a pending automatic OFF.
4. Trouble card with the tablet's Wi-Fi off (NO_ROUTE/NO_ANSWER): "Switch Wi-Fi on" + "Ask again" (pure Trouble.wifiFirst + words, TroubleTest).
5. "has not joined it yet" card: "Wi-Fi settings" button.
6. tests: TabletWifiRulesTest, TroubleTest, Robolectric WifiFollowPreviewTest/DeepSleepPreviewTest scenarios, mutate_link.py + mutate_wifi_glue.py
   patterns; tools/test.sh, assembleRelease, pictures; docs WIFI_FOLLOWS_HOTSPOT.md, HANDOFF, USER_GUIDE; release.
