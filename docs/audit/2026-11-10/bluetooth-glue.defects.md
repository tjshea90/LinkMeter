### D1 [high/certain] Home promises "stays reachable over Bluetooth" without checking that it is
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:481-489, 508-516, 354, 366; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:52-54; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SettingsTab.java:102-106, 177-179; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Status.java:60
- what: The hotspot-off and Standby confirmations decide on BtLink.ready(), which only means: switch on, tablet has an adapter, an address is stored. It ignores the phone's own report (`bt` = off / perm / error / disabled) and whether any Bluetooth call ever succeeded. Scenario: Set up stores the address, the test call fails (range, a pairing question, the unbonded connect refused), or the user later switches the phone's Bluetooth off. Settings still shows a green "Ready", Home says "The phone stays reachable over Bluetooth", the user confirms, the hotspot goes off, and the phone can only be recovered by walking to it.
- fix: Before sending hotspot_off or standby over the hotspot, make one live Bluetooth call (BtLink.call GET /s, about 2-4 s) and show the "anyway" wording if it fails. Also store the time of the last 200 over Bluetooth, clear it when the address changes, and use it together with lastPhone bt == "on" for the Settings chip and the dialog text.

### D2 [medium/certain] "After a phone restart" button does nothing; texts name a Phone tab the tablet does not have
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:365, 153-154, 441-442; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SettingsTab.java:157; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/MainActivity.java:227-229, 311-321; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/ControlTab.java:37
- what: act.openTab("Phone") looks for a tab titled "Phone". The tablet's tabs are Home, Speed, Bands, Control, Screen, Files, Texts, Settings, so nothing happens. v0.2.102 called openTab("Control"). It is the only action offered in the "Can't reach the phone" card besides "Ask again".
- fix: Either make ControlTab.title() return "Phone" now (step D plans that rename), or use "Control" in HomeTab.java:365 and in the three texts until step D lands.

### D3 [medium/likely] Phone listener never retries: stays in "error" or "perm" until Bluetooth is toggled or the service restarts
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:194-197, 217-220, 269-272; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SenderService.java:59-73
- what: listenLocked runs only from start() (once per service life), the Bluetooth state receiver, the end of an accept loop, and bt_setup. If the listen call throws once (for example at the moment Bluetooth comes on), the state is "error" and nothing tries again. If the user grants "Nearby devices" in Android's settings, the state stays "perm". The phone looks healthy over the hotspot but cannot be reached once the hotspot is off.
- fix: Add BtServer.poke(): when the state is perm, error or off and the last try was more than 30 s ago, call listenLocked on a background thread. Call it from Status.json; the tablet's status poll then heals the listener.

### D4 [medium/likely] Home polls a standby phone over Bluetooth every 15 s for as long as the tab is open
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:83, 189-193, 229; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:26, 218, 243
- what: Over Bluetooth the interval is 15 s and a connection is reused for 25 s, so one connection stays open permanently and the phone's processor is woken for every poll. With "Keep the screen on in every tab" this never ends. Standby is meant to let the phone idle for days; a status nobody is reading works against that. When the phone is out of range, the same loop pages it for up to 14 s every 15 s.
- fix: In delayMs: over Bluetooth use 15 s for the first minute after a user action, then 60 s, and stop after about 5 minutes with the existing "Ask again" button. On misses, lengthen to 30 s then 60 s. With intervals above 30 s the idle-close already drops the connection between polls.

### D5 [medium/certain] pollNow() during a running poll starts a second permanent polling chain
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:73-86, 196-202, 470-471, 503
- what: pollNow removes the queued pollTask and posts one. If a poll is running at that moment (a Bluetooth poll takes 1-15 s, or waits behind an action on the BtLink lock), nothing is queued to remove. The running poll then schedules its next run, and the posted one schedules another: two chains from then on. Each switch press calls pollNow twice (now and 4 s later), so polling over Bluetooth can end up at two or three times the intended rate until the tab is hidden.
- fix: At the end of pollTask.run: `if (polling && bg == h && !h.hasCallbacks(this)) h.postDelayed(this, ...)`. Handler.hasCallbacks(Runnable) is public from API 29, which is minSdk.

### D6 [medium/certain] One connection can release the wake lock another connection's request depends on
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Awake.java:12, 25-44; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:298, 319, 331
- what: The link wake lock is a single static, non-counted lock. Every connection's waiting() and every serve() finally calls Awake.linkDone(). Scenario: the tablet's request `hotspot_on` (up to about 22 s) is running with the screen off; a second connection (a stranger sending one bad request, or the tablet's earlier connection ending late after a range drop) reaches waiting() or finally and releases the lock. The processor may then sleep mid-action and the tablet gets "connection lost, may or may not have done it".
- fix: Count holders in Awake: linkBusy increments, linkDone decrements and releases only at zero. Have each serve() remember whether it holds the lock so waiting() and finally release only their own hold.

### D7 [medium/certain] Failed switch actions are reported to the tablet as ok:true
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Actions.java:78, 84, 98, 157-158, 165, 175, 195; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Api.java:176-181; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:465-468
- what: perform() returns failure sentences ("The hotspot could not be switched on: …", "The phone's Wi-Fi did not switch on…") as ordinary text. Api.act wraps any returned text as 200 `{"ok":true,"out":…}`. Home shows it in the normal colour, and any caller that branches on `ok` treats it as done. This is the path every Bluetooth request for hotspot_on, hotspot_off, wifi_on, wifi_off and standby takes.
- fix: In Actions.run, for the perform() names, throw IOException with the sentence when the action plainly failed (hotspot on/off/restart not OK, Wi-Fi not switched); Api.act already turns that into 409 err. Leave the later() path as it is, and keep Standby's two-part sentence as text unless the hotspot part failed.

### D8 [low/likely] Over Bluetooth the phone acts before answering, but callers keep answer-first timeouts
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BandsTab.java:477; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SweepLink.java:81; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/ControlTab.java:274-276; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Call.java:53-55; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:237, 257-258
- what: radio_cycle is sent with 10 s and hotspot_restart / hotspot_off from the Control tab with 15 s, sized for "Sent, the phone will do it in a moment". Over Bluetooth cutsLink is false, so the work runs first: radio_cycle needs roughly 8-13 s or more (4 s off, retries, then radioBack), hotspot_restart up to 3 + 20 s. The Bluetooth guard is readMs + 3 s, so the tablet reports "the Bluetooth connection was lost while asking" or "did not answer in time" for something the phone did, and the band tools count it as failed.
- fix: In Call.reach's Bluetooth.call, use Math.max(readMs, 60000) when the target starts with "/act". For a fresh-connection POST timeout, add "(the phone may still be doing it: look at the status)" to the message at BtLink.java:257.

### D9 [low/certain] A background status poll switches the tablet's Bluetooth on and never off
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:122-147, 161; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:229
- what: Whenever the hotspot route cannot connect and an address is stored, the Home poll calls BtLink.call, which calls adapter.enable() (silent on Android 10). Scenario: the user switched the tablet's Bluetooth off; opening LinkMeter away from the phone switches it back on each time, with no user action, and it stays on.
- fix: Add a flag to BtLink.call (user-initiated or not). Polls pass false: if Bluetooth is off they fail with "Bluetooth is off on this tablet" and Home offers a button. Button presses, Set up, Test and Find pass true and keep the automatic switch-on.

### D10 [low/unsure] hotspot-start can answer "the hotspot is on" before the hotspot is up
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/ShellMain.java:550-552, 588-591, 616-620, 645-647
- what: onTetheringStarted is taken as success. From memory, on Android 15 and older that callback fires when the request is accepted, before the access point is up; whether Android 16 delays it is not verified. A hotspot that then fails is still reported as on. Also: state 12 (switching on) is not treated as already starting, so a second request may fail as a duplicate; and when the state cannot be read after a stop, the helper answers OK and the tablet is told "The hotspot is off."
- fix: After onTetheringStarted, if apState() is readable, poll up to 10 s for 13 and answer ERR with the last state otherwise. Treat 12 at entry as "wait for 13". Have hotspotStop answer a distinct line when the state is unreadable and map it in Actions.hotspotOff to "was asked to switch off; the phone does not say whether it did".

### D11 [low/unsure] Address fallback can store another device's Bluetooth address, and the tablet keeps overwriting a good one with it
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:383-387; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:274-279
- what: If `settings get secure bluetooth_address` prints null, the first complete address in the first four lines of `dumpsys bluetooth_manager` that contain "address" is stored as the phone's own. On a build that hides the adapter's own address but prints a connected or bonded device's in full on such a line, the phone stores the wrong one. The tablet then copies `btmac` from every hotspot status, replacing an address that "Find the phone among paired devices" had proved.
- fix: Restrict the fallback to the adapter's own line: `grep -i -m 2 -E '^[[:space:]]*address:'`. On the tablet, do not replace an address that a successful Bluetooth call has proved unless Set up is run again.

### D12 [low/likely] Blocking Bluetooth calls on the main thread
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SenderService.java:72; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:126-136, 210; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SettingsTab.java:88; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:378; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:44-49
- what: BtServer.start runs in onStartCommand, and the state receiver calls listenLocked in onReceive. The listen call is a blocking binder call followed by a blocking read for the channel number, with no timeout. On the tablet, BtLink.setEnabled(false) closes a socket on the UI thread. Normally tens of milliseconds; a slow Bluetooth stack would freeze the app.
- fix: Run BtServer.start and the receiver's work on a short background thread, and call BtLink.close from a thread in setEnabled as MainActivity.onPause already does.

### D13 [low/likely] The delayed-action thread does not catch RuntimeException
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Actions.java:54-64
- what: later() catches IOException and InterruptedException only. Any RuntimeException from perform() escapes the "linkmeter-delayed" thread and ends the phone's app. This is the same class of bug that B1 fixed in SenderService.serveOne. No concrete throwing path was identified.
- fix: Catch RuntimeException there too, recording it the same way for radio_cycle.

### D14 [low/certain] Setup loses the phone's Bluetooth name and misreports a lost status request
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:97-115, 365-366; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/SettingsTab.java:164-176
- what: name() caches its answer for five minutes, including the empty answer from before the permission existed, so the status read right after bt_setup has no `btname` and "Find" cannot try the phone first. The second step of Set up ignores ok2: if that status request fails, the user is told "The phone did not tell its Bluetooth address. Pair the phone and the tablet…", which is the wrong advice.
- fix: Reset nameAt to 0 in setup() after the grant. In SettingsTab.setUp, when ok2 is false say "could not read the phone's status: <text>; press Set up again".

### D15 [low/certain] A stranger with two connections can push the paired tablet out
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:254-256
- what: When a third connection arrives, the oldest is closed whether or not it has proved itself and even in mid-request. Two unauthenticated connections therefore cut the tablet's connection; the action still runs, but the tablet loses the answer, and a loop of such connections keeps it out. Needs Bluetooth range; denial of service only.
- fix: Track per connection whether it has had one good request (set from the handler) and close the oldest connection that has not; close a proven one only if all are proven.

### D16 [low/certain] Small wrong or surprising texts on Home
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/HomeTab.java:305-306, 377-380, 348
- what: (1) The Wi-Fi meter says "the phone's hotspot is off" whenever the phone answered and the tablet is not on Wi-Fi, even when the status says the hotspot is on (the banner beside it says the opposite). (2) "Not now" on the set-up banner switches the whole Bluetooth link off, the same switch as in Settings, not just the banner. (3) "Turn the hotspot on" is greyed out without a reason when Shizuku is not running and an earlier action message is showing.
- fix: (1) Use the status's `hs` for the subtitle. (2) Store a separate "do not offer" flag for the banner. (3) Put the Shizuku sentence in the banner text when priv is not READY.

### D17 [low/certain] Design note and Control tab text out of step with the code
- where: /home/user/LinkMeter/docs/REMOTE_LINK.md:42, 60; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:319; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/ControlTab.java:84-86
- what: The design note says the per-request wake lock is 30 s; the code uses 120 s. It says band routes are refused over Bluetooth, but `/act` carries net_lte, nrleg_*, restart_modem and radio_cycle there. The Control tab's hotspot-off confirmation still says "To turn it back on, use the phone."
- fix: Correct the two doc lines; reword the confirmation when the Control tab is rebuilt in step D (use Home's link-aware wording).

### D18 [low/unsure] On a foreign Wi-Fi the tablet offers its secret to that network's gateway
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Net.java:52-88; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Call.java:36-41; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/Client.java:150-152, 215-222
- what: The hotspot route targets the default gateway of whatever Wi-Fi the tablet is on and sends X-Token in clear once a TCP connection to port 8765 succeeds. This is older behaviour, but with the phone on standby the tablet is now more likely to be on another network, and a captured secret plus Bluetooth range is enough to command the phone without the hotspot password. A gateway that accepts the connection also stops the Bluetooth fallback, because the request counts as possibly delivered.
- fix: Smallest: remember the gateway address that last answered 200 as the paired phone and send the secret only to that address or to a typed one; any other gateway counts as "not on the hotspot" and goes straight to Bluetooth. Proper fix later: sign Wi-Fi requests as on Bluetooth.

### D19 [low/unsure] No wake lock between accepting a Bluetooth connection and the first request
- where: /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtServer.java:241-261, 291-327; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/LinkServer.java:55-57, 68; /home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/BtLink.java:28, 182
- what: The first acquire is in working(), after a whole request head has been read. Starting the thread, making the nonce and writing the greeting rely on the Bluetooth stack keeping the processor awake for a moment after an incoming connection. If a deeply sleeping phone suspends first, the greeting waits for the next wake-up and the tablet gives up after 6 s with "a device answered the Bluetooth call, but not as LinkMeter".
- fix: In acceptLoop, right after accept() returns, take the link lock for 5 s; the first waiting() after the greeting releases it (with the holder count from the wake-lock fix).
