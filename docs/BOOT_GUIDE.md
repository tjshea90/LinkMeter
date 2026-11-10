# Start the phone after a restart: how to use it

(Written for the person using the two devices. The design and the facts behind it are in `BOOT_FLOW.md`.)

Install the newest release on BOTH the phone and the tablet first: https://github.com/tjshea90/LinkMeter/releases

## What it does for you

After the phone restarts, Shizuku is dead. This gets it going again with far fewer taps:

| Step | Who does it now |
|---|---|
| Tablet makes its own Wi-Fi hotspot (like NetShare, but with the SAME name and password every time) | the tablet, by itself |
| Phone joins the tablet's hotspot | the phone, by itself (after you joined it by hand once) |
| Wireless debugging on | the phone, by itself (after the one-time setup) |
| Open Shizuku | you: tap the notification "Shizuku isn't running" |
| "Allow wireless debugging on this network?" | you: tap Allow (tick "Always allow") |
| Pairing with the number | you, but normally only the first time |
| Start in Shizuku | you: tap Start |
| Phone starts its hotspot, tablet's hotspot stops, tablet joins the phone | the two devices, by themselves |

Android does not let any app do these, so they stay with you: switch Wi-Fi on (tablet or phone), tap Allow in the system
question, type the pairing number, press Start inside Shizuku, open Shizuku without a tap.

## Setting it up (once)

1. Update both devices to the newest release.
2. Phone: nothing to do yet. Tablet: open LinkMeter > Phone tab and scroll to the card "Start the phone after a restart".
3. While everything works as usual (Shizuku running on the phone, tablet on the phone's hotspot) tap **One-time setup** > Do it.
   This gives LinkMeter on the phone the permission to switch wireless debugging on, and turns on "Boot assist". **Undo the setup**
   takes all of it back.
4. Optional: **Keep the pairing**. Android forgets a wireless-debugging pairing that was not used for 7 days, and you would have to
   pair again. This switch stops that (it is the developer option "Disable adb authorization timeout"). **Forget after 7 days**
   puts it back.

## After every restart of the phone

1. Phone: restart it, **unlock it with your PIN** (Android does not run apps before the first unlock), and make sure its
   **Wi-Fi is on** (the phone's hotspot is off after a restart, as usual).
2. Tablet: Phone tab > **Start everything**. (On the Home tab the button "Phone not answering? Start it after a restart"
   takes you there.) Allow the location question the first time: Android 10 wants it before an app may make a hotspot; LinkMeter
   does not read your location. If the tablet's Wi-Fi is off, the panel to switch it on opens first.
3. **The very first time only:** the card shows the tablet hotspot's name (`DIRECT-LM-Tablet`) and its password. On the phone join
   it like any Wi-Fi (Settings > Connections > Wi-Fi; if it is not listed, Add network and type them). If Android says the network
   has no internet, choose to stay connected. From then on the phone joins it by itself.
4. Phone: a notification "Shizuku isn't running" appears. Tap it, then **Start** in Shizuku. If Android asks "Allow wireless
   debugging on this network?", tap Allow (and tick "Always allow"). If Shizuku asks for pairing: Developer options > Wireless
   debugging > Pair device with pairing code, then type the number into Shizuku's notification.
5. Watch the card on the tablet: it lists the steps and says what it is waiting for. When it says the tablet is on the phone's
   hotspot and Shizuku is ready, you are done.

## If it gets stuck

The card always says where it stopped and what to do. The usual causes:

- "The tablet could not start its own hotspot": use NetShare as before; LinkMeter carries on as soon as the phone shows up on it.
- The phone is not found: its Wi-Fi is off, it has not joined the tablet's hotspot, or it is still locked (LinkMeter on the phone
  starts only after the first unlock).
- Wireless debugging does not switch on by itself: run **One-time setup** again (needs Shizuku running once).
- Shizuku stops when the phone leaves the tablet's hotspot: the card says so at the last step. Tell me; a USB-cable route exists
  as a fallback (not built yet).

## What I need from you after the first real try

A screenshot of the card (the list of steps) and of the phone's Wi-Fi list. Nothing here has been seen on the real devices yet:
the Wi-Fi Direct hotspot, the phone joining it by itself and Shizuku surviving the swap are the three things to confirm.
