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
- **Texts:** off until you switch them on at the phone (card "Texts"). The list opens at once and keeps itself up to date (a new text
  appears within a second or two, no need to open the conversation). Tap a conversation to read it, hold one to select several (pin,
  mark read, archive, delete), tap the magnifier to search names and texts, tap + to write. In a conversation: hold a message to copy,
  forward, delete or see its details; quick replies appear when the last text is a question; a text you are typing is kept until you
  send it; reactions from iPhones ("Loved ...") show on the message they are about. **Delete** removes it on the tablet and asks the phone
  to delete it too; most phones only let their own Messages app do that, then the texts stay on the phone and the strip says so (Undo
  brings them back here). On a wide tablet screen the list stays on the left. The menu (the three dots) has unread only, archived,
  mark all read, refresh and Texts settings. A fresh verification code gets a **Copy code** button (in the list and under the text).
  **Texts settings** (menu): keep the saved copy (it is encrypted, with a key that stays inside the tablet), hide the words in the
  list, **lock Texts with the tablet's screen lock** (asks again after 2 minutes away; needs a PIN, pattern or password set in
  Android), hide Texts from screenshots and the recent apps picture, erase everything saved here. Pairing again erases it too. Texts
  themselves (SMS/MMS) cannot be end-to-end encrypted by any app, and chats that Google Messages sends as RCS are not shown.
- **Speed, Screen, Files:** as before.

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
- Texts (use the newest release on BOTH devices): does the list open at once? Does a new text show up by itself without opening it? Do
  Delete, Archive, Pin, Search and a picture message work? Does Delete say "Deleted on the phone" or "The phone kept it"?
  With Texts settings > "Lock Texts with the screen lock" on: does it ask after 2 minutes away, and not before? Does a fresh code text
  show Copy code, and does it copy only the digits?
- Glide typing (SwiftKey): Screen tab > Options > Keyboard > "Test the keyboard". In box 1 (a plain text box) and in box 2 (the Type
  button's field, with what it would send written underneath): does a glided word work? Tell me which of the two glides, and what the
  last lines of the test say (version, keyboard, counts).
- The Safety check on the phone: does it read clearly?
