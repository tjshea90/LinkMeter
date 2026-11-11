# LinkMeter: a short guide

LinkMeter is one app on two devices. The **phone** shares its hotspot and does what the tablet asks. The **tablet** shows the
phone's signal, speed and screen, and switches the phone's hotspot, Wi-Fi and power from where you sit, so that you need the phone
in your hand as little as possible.

**Status:** everything described here was written and tested on a computer, but **has not been run on your phone or tablet yet**.
When you try it, the most useful thing you can tell me is what happened and what read wrongly (the list is at the end).

Use the newest release (https://github.com/tjshea90/LinkMeter/releases) on BOTH devices. Mixing releases mostly works, but not
always.

## 1. Setting it up (once)

1. Install the same release on both devices. Open it on each and choose **This is the phone** or **This is the tablet**.
2. **Phone:** the first card says what to do first. Press **Start** (allow the phone permission and notifications when Android asks).
3. **Pair the tablet.** Switch the phone's hotspot on and join it with the tablet. On the phone press **Pair tablet** (it stays open
   for 2 minutes), then open LinkMeter on the tablet. It pairs by itself; Home then shows the phone.
4. **Shizuku (phone).** Shizuku is what lets LinkMeter switch things on the phone. Start it (it needs wireless debugging) and press
   **Allow LinkMeter in Shizuku**. Then, on the phone, press **Allow everything** in the card "Shizuku and permissions".
   Shizuku stops every time the phone restarts: see section 4.
5. **The Bluetooth link (so that the tablet can switch the hotspot ON again).** On the tablet, while it is on the phone's hotspot:
   Settings > Bluetooth link > **Set up**, then **Test**. The phone's switch "Let the tablet reach this phone over Bluetooth"
   (on the phone, card "Tablet link") must be on, and Bluetooth must be on at the phone.
6. In Android's settings set LinkMeter to **Unrestricted** battery use on both devices, so that Android does not stop it.

## 2. Every day (on the tablet)

- **Home:** **Hotspot** (switch the phone's hotspot on or off), **Screen** (put the phone's screen to sleep or wake it) and
  **Standby** (hotspot off and screen off: the phone then idles for days; **Hotspot** wakes it again over Bluetooth). Each button
  that cuts the link asks first and says what it needs.
- **Phone tab:** the battery saver (**Automatic** switches off background scanning and the always-on display and shortens the
  screen timeout while the tablet uses the hotspot, and puts everything back by itself), the phone's **Wi-Fi**, **Restart** and
  **Power off** (they ask first: after them someone has to touch the phone), and **If something breaks**.
- **Signal tab:** what the phone is on now (**Scan**), **4G only** or **5G allowed**, and **Compare the two** (measures the upload
  speed of both in random order; about 5 minutes, uses mobile data; the phone is put back as it was). **Search for a network
  again** and **Phone info screen** are under Tools.
- **Screen tab > Type:** opens the tablet's keyboard, and what you type goes to the phone as you type it. Glide (swipe) typing and
  suggestions work (Options > Keyboard > "Suggestions and glide typing"); switch that off to send exactly the letters you tap, which is
  better for codes, web addresses and passwords. "Incognito" (off) asks the keyboard not to learn what you type; some keyboards then
  also switch glide typing off. The phone never types `*` or `#`.
- **Speed, Screen, Files, Texts:** as before. Texts are off until you switch them on at the phone (card "Texts").

## 3. If something breaks

On the phone, LinkMeter > **Restore everything** puts the network types, the hotspot band and the battery saver back as they were,
and switches airplane mode off and mobile data on (it needs no tablet and no internet). The same button is in LinkMeter's
notification. **Safety check** shows
what LinkMeter still has changed. Without Shizuku the app cannot undo anything itself: "Without Shizuku: what to do" lists the
steps by hand. The whole story is in `docs/SAFETY.md`.

## 4. After the phone restarts

The phone has to be unlocked once, and Shizuku has to be started again. The tablet's Phone tab has a card **After a phone
restart** that helps (`docs/BOOT_GUIDE.md`). LinkMeter itself starts by itself ("Start LinkMeter when the phone starts" on the
phone's screen). Until Shizuku runs, the tablet can read the status but cannot switch anything, and the phone's first card says so.

## 5. What I would like to hear after you try it

Tell me what happened, in your own words, for any of these:
- Settings > Bluetooth link > Set up, then Test: did it say Ready?
- Hotspot off, then Hotspot on from the tablet; Standby, then waking the phone.
- The battery saver: Automatic, then Off, and the "Battery saver" line on Home.
- The Screen tab left open while the phone sleeps (the tablet is meant to rest after a while, and wake when you touch it).
- The phone's own screen: does the first card name the right thing? Do Stop, Forget all tablets and Switch role ask first?
- The Signal tab: Scan, the comparison, Search for a network again, Phone info screen.
- Glide typing in the Screen tab's Type keyboard (SwiftKey): does a glided word arrive on the phone? If it does not: glide one word,
  close the keyboard, open Options, and send me the line that starts with "Keyboard:" (Options > Copy this information).
- The Safety check on the phone: does it read clearly?
