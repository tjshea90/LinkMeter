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
- **Hotspot off puts the phone to sleep, Hotspot on wakes it.** **Hotspot off** switches the phone's hotspot off, then its location off (if it was
  on), then airplane mode on, then its screen off, so it uses almost no battery; in airplane mode it gets **no calls or texts**. **Hotspot on** ends
  airplane mode first, then switches the hotspot on; it leaves location and the screen alone (location goes back on in the Phone tab, **Location on**).
  Airplane mode takes Bluetooth away on this phone, and Bluetooth is how the tablet wakes it, so the phone only stays in airplane mode while
  Bluetooth stays on (it checks, and switches airplane mode off again if not); the tablet only asks for it right after the Bluetooth link answered.
  The question has **Turn it off**, **Hotspot only** (the old plain Hotspot off) and **Cancel**; Settings > This tablet > "Hotspot off also puts the
  phone to sleep" turns it off for good. Standby is unchanged (calls and texts still arrive). Home shows the phone's **Airplane mode** and
  **Location**. Read `docs/DEEP_SLEEP.md` for what is not known yet.
- **The tablet's own Wi-Fi follows the hotspot** (Settings > This tablet > "Switch the tablet's Wi-Fi with the hotspot", on unless
  you switch it off). After **Hotspot on** the tablet's Wi-Fi is switched on, so that it can join the hotspot; after **Hotspot off**
  or **Standby** it is switched off, so that it does not keep searching. Switching it off happens only after the phone has said the
  switch worked; switching it on happens as soon as the phone has taken "Hotspot on" (a phone asleep in airplane mode needs up to a minute
  more to start the hotspot, and the tablet joins it by itself once it is up). The Wi-Fi is left alone when the tablet is on another
  Wi-Fi network (the one at home). **Android does not let an app switch
  Wi-Fi by itself** (since Android 10): LinkMeter tries every way it has and then opens Android's own Wi-Fi switch, and the one tap
  on it is yours. The line under the buttons says which of the three happened ("The tablet's Wi-Fi is on" only after LinkMeter has
  checked it; "Switch the tablet's Wi-Fi on in the panel that opened" when it asked you). (Only if Shizuku runs on the tablet itself and LinkMeter is
  allowed in it, no tap is needed; LinkMeter has no button on the tablet to ask Shizuku for that, so count on one tap.) When the tablet's Wi-Fi is off and the phone's hotspot is on, or the phone cannot be reached at all, Home shows a card with
  **Switch Wi-Fi on**; when the Wi-Fi is on but has not joined the hotspot, the card has **Wi-Fi settings**. (Android's own
  Wi-Fi preferences also have "Turn on Wi-Fi automatically", which may bring the Wi-Fi on near a saved hotspot without any tap; that is
  Android's, not LinkMeter's, and not tried here.)
- **Phone tab:** the battery saver (**Automatic** switches off background scanning and the always-on display and shortens the
  screen timeout while the tablet uses the hotspot, and puts everything back by itself; this is LinkMeter's own saver, it is NOT
  Android's Battery saver, and the tab shows both lines apart: "LinkMeter's saver" and "Android's Battery saver", the second as
  Android itself says it; **Also switch on** under it makes LinkMeter turn Android's own Battery saver on with its saver and back
  off with it: off until you choose it, not tried on this phone yet), the phone's **Wi-Fi**, **Restart** and
  **Power off** (they ask first: after them someone has to touch the phone), and **If something breaks**.
- **Signal tab:** what the phone is on now (**Scan**), **4G only** or **5G allowed**, and **Compare the two** (measures the upload
  speed of both in random order; about 5 minutes, uses mobile data; the phone is put back as it was). **Search for a network
  again** and **Phone info screen** are under Tools.
- **Screen tab > Type:** opens the tablet's keyboard, and what you type goes to the phone as you type it. Glide (swipe) typing and
  suggestions work (Options > Keyboard > "Suggestions and glide typing"); switch that off to send exactly the letters you tap, which is
  better for codes, web addresses and passwords. "Incognito" (off) asks the keyboard not to learn what you type; some keyboards then
  also switch glide typing off. The phone never types `*` or `#`.
- **Texts:** off until you switch them on at the phone (card "Texts"). The list opens at once and keeps itself up to date (a new text
  appears within a second or two, no need to open the conversation). Under each name the list shows the last message, also for a group
  text or a picture message (a picture alone reads "Attachment"; when the phone's list has no words for one, the tablet asks for that
  conversation's newest messages itself). **The Texts button in the bottom bar lights up with a small red number of new texts**, also
  while you are on another tab (only while LinkMeter is in front; opening a conversation here counts it as read; muted and archived
  ones do not count). Tap a conversation to read it, hold one to select several (pin,
  mark read, archive, delete), tap the magnifier to search names and texts, tap + to write. **A conversation opens at its newest
  message and stays there**, also when the keyboard opens and when new texts come; scroll up to read older ones and it stays where you
  are: a round button at the bottom right then shows how many new texts came and takes you back to the newest. Hold a message to react,
  copy, forward, delete or see its details; quick replies appear when the last text is a question; a text you are typing is kept until
  you send it; several texts can be sent one after the other without waiting, and each one's "Sending…" turns into "Sent" or
  "Delivered" by itself. **Delete** removes it on the tablet and asks the phone
  to delete it too; most phones only let their own Messages app do that, then the texts stay on the phone and the strip says so (Undo
  brings them back here). On a wide tablet screen the list stays on the left. The menu (the three dots) has unread only, archived,
  mark all read, refresh and Texts settings. A fresh verification code gets a **Copy code** button (in the list and under the text).
  **Translate to Spanish (Mexico)** (a conversation's menu, the three dots, > "Translate my texts to Spanish (Mexico)"): write in English
  and press Send; the Spanish as it is spoken in Mexico appears in the box for you to check, and Send again sends it (Undo gives your
  English back, "Send my English" sends it untranslated). Hold a text you received > "Translate to English" shows its English under it.
  The words of that text go over the internet to a translation service (MyMemory, or Google Translate when MyMemory is busy); nothing
  else does. (Gboard's own translation does not exist on this tablet: Google does not offer it on Android Go devices.)
  **Texts settings** (menu): keep the saved copy (it is encrypted, with a key that stays inside the tablet), hide the words in the
  list, **lock Texts with the tablet's screen lock** (asks again after 2 minutes away; needs a PIN, pattern or password set in
  Android), hide Texts from screenshots and the recent apps picture, erase everything saved here. Pairing again erases it too. Texts
  themselves (SMS/MMS) cannot be end-to-end encrypted by any app, and chats that Google Messages sends as RCS are not shown.
  **Reactions:** press and hold any message and tap an emoji (thumbs up, heart, laughing, surprised, sad, thanks, or + for many more):
  it sits as a small emoji on the corner of that message. It reaches the other person the way Google Messages and iPhones send
  reactions by text (Liked “…”, Reacted 😮 to “…”): Google Messages shows it on their message too, an iPhone shows those words. The
  same emoji again takes it back; tap the small emoji to see who reacted. Their reactions (also from an iPhone set to Spanish) sit on
  your message the same way. A text of only emoji that you type is drawn big. **Attachments:** the paperclip puts large cards above the box (a photo's
  picture, a PDF's first page, the name and size); tap a card to see it full screen before sending (pinch or double-tap to zoom a photo,
  Previous/Next for a PDF, the start of a text file; Remove there or the cross on the card takes it off). **Contacts:** a conversation's
  menu has Add to contacts or Rename contact; Texts menu > Contacts lists the phone's contacts (search, + for a new one, tap one to send a
  text or rename it). A name is saved on this tablet at once and on the phone too when "Also save on the phone" is on and the phone lets
  LinkMeter (it asks the phone to allow that; Shizuku must be running); the screen says which of the two happened. **If it says "The phone is
  slow to answer"** the phone is there but did not answer in time; LinkMeter asks again by itself. Texts menu > Connection details shows how
  long each request took (Copy puts it on the clipboard: it holds no texts).
- **Calls (2026-11-16): the phone's phone app, from the tablet.** The **Calls** tab has a large **Keypad** (a long press on 0 types a +, Backspace
  deletes one digit, a long press on it clears the number, a long press on the number pastes one; typing 666 finds *Mom* by the letters on the
  keys), **Recents** (missed calls in red, calls in a row from one person as one line, the green button calls back, a tap opens a menu: Call,
  Message, Rename contact, Copy number) and **Contacts** (the same contacts as Texts: a name given in either place shows in both; the chat button
  opens the conversation, the green one calls). While the phone rings the **Calls** tab shows Answer and Decline, and a green strip with the
  same buttons shows at the top of EVERY tab; while a call is on it shows the length, **Mute**, **Audio** (headset, speaker, earpiece), **Phone
  screen** (opens the Screen tab: use it for "press 1 for ..." menus, which only the phone's own screen can type) and a large red **End call**.
  A **Call** button in a Texts conversation asks "Call Mom?" and dials. The Calls button gets a number for missed calls you have not looked at.
  **Set up once:** on the phone's own screen (LinkMeter, Phone tab, the **Calls** card) switch on **Let the tablet make and answer calls** and
  tap **Allow calls access** (Shizuku gives the permissions), or press **Allow it for me** on the tablet's Calls tab. It stays off until you
  switch it on. **Never dialed from the tablet:** emergency numbers (911 and the others: use the phone itself) and codes with # (or more than one *);
  a number that only starts with * (like *67) is an ordinary call.
  **The headset belongs to the PHONE, not the tablet.** The call's sound only exists inside the phone (Android lets no app take it out, and the
  tablet cannot play a call it is not part of), so pair the headset with the phone and keep it within a few metres of the phone. The chip at the top
  of the Calls tab (for example *Sony WH-1000XM4 · connected*) opens the **Headset** screen: **Connect headset** connects the one you chose
  (LinkMeter switches the phone's Bluetooth on first if it is off), **Pair a headset** looks for one in pairing mode and pairs it, **Choose
  another** picks among the paired ones, and **Phone's Bluetooth settings** opens the phone's own screen in the Screen tab if the button cannot do
  it (most headsets also connect by themselves when you switch them on). If the headset drops, the screen counts the drops: move the phone closer.
  Calls need the hotspot link (Wi-Fi), like Texts; they do not work over the Bluetooth-only link. Read `docs/PHONE_CALLS.md` for how it works and
  what is not known until a phone tries it.
- **The floating phone, the headset's battery and the headset on the tablet (2026-11-19).** *Floating phone:* when the phone rings or a call is
  on, a small dark card floats over whatever app you are in: who and how long, a green button (Answer, or Call when you typed a number) and a red
  one (Decline, End), the headset's battery, two Bluetooth buttons and an arrow that opens a **dial pad** (it steps aside while LinkMeter itself is
  open, because LinkMeter shows the call on its own; **Show the phone now** keeps it there). Drag the top strip to move it; it
  slides to the nearer side edge and remembers the place. After a call it stays six seconds ("Call ended · 3:12"). The cross closes it when no
  call is on. **Set up once:** Settings > **Floating phone** > **Allow display over other apps** (Android's own screen opens: switch it on for
  LinkMeter and come back). **On a Go tablet (Android says "Feature not available" there) use Settings > Floating phone > Turn on with
  Accessibility** instead: open "LinkMeter floating phone" in Android's Accessibility settings and switch it on (it only draws the window;
  it asks for no window content and receives no events). **Show the phone now** (Settings, the small blue handset at the top of the Calls tab, or the notification's *Show
  phone*) brings it up without a call, for dialing. It only controls the phone: the call and its sound stay on the phone and the headset, and
  emergency numbers and codes are refused here like everywhere. It keeps a small service running (one quiet question to the phone every
  20 seconds while the tablet is awake and calls work); the Settings switch **Pop up when the phone rings or a call is on**, or the
  notification's *Turn off*, stops it. *Battery:* while the headset is connected to the phone, its battery shows beside the chip at the top of
  the Calls tab, on the Headset screen (big icon) and on Home ("Headset"). A dash means the headset does not tell the phone its level
  (some only do while a call or music plays): nothing is invented. *The headset on the tablet too:* the Headset screen has a card **This
  tablet** and the floating phone has a second Bluetooth button (Phone / Tablet). The headset must already be paired with the tablet (the card
  has **Pair with this tablet**). **One connection at a time:** connecting the headset to the tablet first disconnects it from the phone, and
  connecting it to the phone first disconnects it from the tablet (not while a call is on: end the call first). If your headset can keep two
  connections ("multipoint"), switch on **My headset can stay connected to both** (the This tablet card) and nothing is disconnected. **Android 10 does not let an app connect a headset by
  itself**: unless Shizuku runs on the tablet, **Connect to tablet** opens Android's Bluetooth list and you tap the headset once (the app says
  so; it never claims it connected before the tablet's own list shows it). Read `docs/FLOATING_PHONE.md` for how it works and what is not
  known until a device tries it.
- **Speed, Screen, Files:** as before.
- **Fast Wi-Fi (2026-11-12): the tablet's internet without the hotspot's speed limit.** Home > "Internet for this tablet" has two
  choices: **Hotspot** (as always: the carrier limits hotspot traffic to 5 Mbit/s) and **Fast Wi-Fi**. With Fast Wi-Fi the tablet stays
  on the phone's hotspot, but every app's traffic goes through LinkMeter on the phone, which makes the connections itself, so the
  carrier sees ordinary phone traffic. The first time, Android asks whether LinkMeter may set up a VPN: press **OK** (the key icon at
  the top of the tablet's screen then shows while it is on). After that it is one tap. Fast Wi-Fi stays on until YOU switch it off (**Hotspot** on Home, the tile, or the notification's **Turn off**): a restart of the
  tablet or of the app does not end it, and if Android takes the VPN away (another VPN app was switched on) LinkMeter puts it back, or the
  card says Android needs your OK again (**Give the OK**). **On the lock screen and in the custom launcher Fast Wi-Fi pauses** (so Android's "Device connected to ..." row never shows there; the card says "Fast Wi-Fi is paused"), and **it starts again by itself** a moment after you unlock into Quickstep (the Launcher app tells LinkMeter when; update both apps on the tablet). If it ever does stop, Settings > Fast Wi-Fi > "What changed it lately" says why; if the phone's LinkMeter does not answer (or the hotspot is off) the tablet simply uses the normal
  hotspot and Fast Wi-Fi takes over again by itself when the phone answers. **Quickest switch:** add the **Fast Wi-Fi** tile to the
  tablet's quick settings (swipe down twice from the top, press the pencil, drag the tile in): one tap from anywhere. The notification
  has **Turn off**. Install the newest release on **both** devices: the phone does the relaying. Settings > Fast Wi-Fi: leave "Steer
  apps to their faster way" on. **Without the hotspot at all:** Settings > Fast Wi-Fi > Fast Wi-Fi's Wi-Fi > **Its own Wi-Fi**. Then
  **Fast Wi-Fi** on Home switches the phone's hotspot off and opens the phone's own Wi-Fi network DIRECT-LM-LinkMeter (2.4 GHz), which
  the tablet joins by itself (the first time Android may ask "Allow suggested Wi-Fi networks?": tap **Yes**, or pick the network once
  in Wi-Fi settings); **Hotspot** switches back to the normal hotspot. Try this if Fast Wi-Fi is still slow over the hotspot.
  Read `docs/FAST_WIFI.md` for how it works and what is not known yet.

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
- Calls: after switching **Let the tablet make and answer calls** on, does the Calls tab say it is ready? Does a call placed from the Keypad ring the other phone and
  play on your headset? Does a ringing call show on the tablet, and do Answer, End, Mute and the audio button work? Does **Connect headset** connect it (or what does it say)?
- The floating phone: after **Allow display over other apps**, does the card pop up over another app when the phone rings, do **Answer**, **End**
  and the pad's **Call** work, does the timer count, can you drag it and does it keep its place, and does it go away six seconds after the call?
  Does it feel slow on this tablet, and does it still pop up after the tablet slept for a while (if not, how long was it asleep)?
- The headset's battery: does the Calls tab / Headset screen / Home show a percentage for your headset, or a dash (then: which headset)? Does it
  move when you use the headset? The two Bluetooth buttons: does **Phone** disconnect and connect the headset, and does **Tablet** connect it to
  the tablet (or open Android's list, and then one tap does it)? Does connecting to the tablet disconnect it from the phone first (and the other way round)? When the tablet cannot disconnect by itself, does the list open and say what to tap?
- The headset's volume (new, install on BOTH devices): with the headset connected to the phone, Phone tab > **Headset volume**: do the louder and
  quieter buttons change what you hear in the headset (Media: play a song on the phone; Call: during a call), and does the number move? Does the same row
  on the floating phone do the same, and does its chip switch between Media and Call? If a button does nothing, what does the line under the rows say
  (it may say Android asked on the phone's screen to allow louder sound)? Does turning the volume with the headset's own buttons show on the tablet?
- Round of 2026-11-21 (install the newest release on BOTH devices; NOT RUN ON A DEVICE): (1) Hotspot on but the tablet's Wi-Fi off: does **Switch Wi-Fi on** now open Android's Internet panel
  within 2-3 seconds (and, if not, the Wi-Fi panel, then Wi-Fi settings), and does the line under the banner say what it just did? (2) The phone icon and the Bluetooth button in the Phone tab
  (and the buttons of the floating phone): does every press now show at once (the button darkens for a moment) and work the FIRST time? Does the small **Headset** chip react too? (3) Texts >
  **New message**: one screen, like Messages: a To line at the top (type a name or a number; a typed number becomes a pill when you press Next, leave the box or Send), the suggestions under it, the message box
  and **Send** at the bottom. Does typing a number, then the message, then Send work with no separate "Next" step, and does the conversation open afterwards with your text sent once? (4) The Launcher's
  **Back to custom** tile or icon with Pocket return on: after it, is the phone's Android Battery saver ON (Settings > Battery on the phone), also when it was off before? If it was on already, was it left on?
  The Phone tab's line for the last pocket run says if Android still reported the saver off after three tries.
- Round of 2026-11-26 (install the newest release on BOTH devices; NOT RUN ON A DEVICE): (1) **Phone and Calls tabs after a call**: switch to another app, make or take a call (from the tablet or on the phone),
  hang up, come back to LinkMeter: do the call strip and the Calls tab show at once that the call is over, and does the Phone tab answer? (2) **Floating phone**: the card has a **Text** button; the arrow now opens three tabs,
  **Keypad**, **Recents** (the phone's call list: a line's phone button calls, its bubble opens a text) and **Contacts** (type in the search box: the keyboard of your keyboard app should come up and the card should
  move to the top so it is not covered). Does the keyboard really appear in the floating card on this tablet (Android 10 Go, Accessibility way)? (3) **The small texting card** (the Text button): a list of conversations, one
  conversation with a box and Send, **+** for a new message by name or number. Does a text sent from it reach the phone and show in the Texts tab, and does an answer show in the card by itself? (4) **Fast Wi-Fi**: pages should
  start a little sooner (Settings > Fast Wi-Fi > **Open known servers at once**; switch it off if an app misbehaves). Does browsing feel faster than before, and does any app or site now fail that worked?
- Settings > Bluetooth link > Set up, then Test: did it say Ready?
- Hotspot off, then Hotspot on from the tablet; Standby, then waking the phone.
- Hotspot off (deep): after you press **Turn it off**, does the phone end up with the hotspot off, location off, airplane mode on and the screen off
  (Home's lines Airplane mode and Location, and the sentence under the buttons)? Does **Hotspot on** from the tablet then wake it (airplane mode off,
  hotspot on)? If the sentence says "Airplane mode was switched off again: Bluetooth could not stay on", this phone does not keep Bluetooth in
  airplane mode: switch the setting off and tell me. How long did Hotspot on take from airplane mode?
- The tablet's Wi-Fi with the hotspot: after Hotspot on, did the panel with the Wi-Fi switch open, and did one tap on it join the
  hotspot? After Hotspot off, did the Wi-Fi go off (after your tap)? Was one tap fine for you, or would you rather have it with no tap
  at all (that needs a second, tiny app on the tablet that Android still allows to switch Wi-Fi: tell me and I build it)?
- The battery saver: Automatic, then Off, and the "LinkMeter's saver" and "Android's Battery saver" lines on Home. Then **Also switch on**
  (Phone tab): does the phone's own Battery saver (Power saving) really come on, and go off again when the saver ends? Does the
  hotspot stay up, is the tablet as fast, and does Standby still wake from the tablet? If the phone does not follow, Probe phone
  prints what its settings say (`low_power`, `psm_switch`): send me those lines.
- The Screen tab left open while the phone sleeps (the tablet is meant to rest after a while, and wake when you touch it).
- The phone's own screen: does the first card name the right thing? Do Stop, Forget all tablets and Switch role ask first?
- The Signal tab: Scan, the comparison, Search for a network again, Phone info screen.
- Texts (use the newest release on BOTH devices): does the list open at once? Does a new text show up by itself without opening it? Do
  Delete, Archive, Pin, Search and a picture message work? Does Delete say "Deleted on the phone" or "The phone kept it"?
  With Texts settings > "Lock Texts with the screen lock" on: does it ask after 2 minutes away, and not before? Does a fresh code text
  show Copy code, and does it copy only the digits? Is the list quicker to load than before (install the newest release on the PHONE too:
  that is where the speed fix is)? If it still says "slow to answer" often, open Texts menu > Connection details and send me the lines.
  Press and hold a text: does the emoji row appear, does the emoji sit on the message's corner, and does the other person's phone show it
  on their message (Google Messages) or as "Liked “…”" (iPhone)? Attach a photo and a PDF: are the cards large and does a
  tap show them full screen? Add a number to your contacts: does the phone's Contacts app have it afterwards?
  Texts round 4 (2026-11-12): does a conversation open at its newest message and stay there when the keyboard opens? Scroll up, then let
  a text come: does the round button at the bottom right count it, and does a tap take you back? Send a text, turn the tablet's screen off
  and on (or attach a photo first): does "Sending…" turn into "Sent" by itself? Is the Texts button lit with the right number while you are
  on Home? Does every row of the list show its last message?
- Glide typing (SwiftKey): Screen tab > Options > Keyboard > "Test the keyboard". In box 1 (a plain text box) and in box 2 (the Type
  button's field, with what it would send written underneath): does a glided word work? Tell me which of the two glides, and what the
  last lines of the test say (version, keyboard, counts).
- The Safety check on the phone: does it read clearly?
