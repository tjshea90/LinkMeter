# Defect hunt: Bluetooth link + remote power (B2/B3/B4), read against real Android

All `X.java:n` citations are files in `/home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/`. Nothing in the repo was changed. The two offline suites were compiled and run in the scratchpad and pass (LinkProtocolTest 1,525 checks, ReachTest 301,253). Android facts were checked against the SDK stubs in `/opt/android-sdk/platforms/android-36` where they are public API; framework internals (Bluetooth stack pairing, shell permissions, tethering service) are from memory and marked as such.

Build levels: minSdk 29, targetSdk 34, compileSdk 34 (`/home/user/LinkMeter/app/build.gradle`).

## 1. Permissions — verdict: correct for the user's two devices

- Manifest: `BLUETOOTH_CONNECT`, plus `BLUETOOTH` and `BLUETOOTH_ADMIN` with `maxSdkVersion="30"` (`/home/user/LinkMeter/app/src/main/AndroidManifest.xml:39-45`). The tablet (API 29) gets the two legacy ones at install; the phone (API 36) needs the runtime one.
- The tablet needs neither location nor `BLUETOOTH_SCAN`: it only calls `getRemoteDevice`, `cancelDiscovery`, `createInsecureRfcommSocketToServiceRecord`, `getBondedDevices`, `getName`, `enable` (BtLink.java:128, 162-169, 282-285), all covered by the legacy pair on API 29.
- `BluetoothAdapter.enable()` on API 29: allowed. The API 36 stub says it always fails only for apps targeting 33+ on Android 13+ devices; that check does not exist on Android 10.
- SecurityException coverage: every Bluetooth call is inside a `RuntimeException` catch (BtServer.java:65, 109, 146, 217, 242, 402; BtLink.java:76, 129, 170, 176, 186, 295). One gap, not reachable on the user's devices: `d.getName()` at BtLink.java:307 is outside any RuntimeException catch.
- The phone's permission currently comes only from `bt_setup` (`pm grant`, BtServer.java:365). The phone-screen "Allow Bluetooth" button is still step D; a grant made in Android settings is not noticed until the service restarts (defect 3).

## 2. Insecure RFCOMM to an unbonded device by address — verdict: likely works silently, not verified

- The API 36 stub for `createInsecureRfcommSocketToServiceRecord` still documents the unauthenticated link key and mandatory encryption for Bluetooth 2.1+ devices.
- From memory of the AOSP stack: a pairing not started through `createBond` with no bonding and no MITM flags is treated as temporary Just Works and auto-accepted on both sides. So no dialog is expected on Android 10 or on AOSP 14/15. Not verified for One UI on Android 16.
- Consequence if true: no link key is stored, so every reconnect repeats the Just Works step (about 1 s extra).
- If a dialog does appear: only SettingsTab.java:209-211 mentions pairing. The normal failure text (BtLink.java:178) does not, and the 14 s connect limit (BtLink.java:27) is too short for a person to answer a dialog on the phone. Accepting once normally makes later calls silent.
- The phone must be connectable (page scan) while not discoverable; AOSP sets this when Bluetooth is on. Not verified on Samsung.

## 3. Shell commands on Android 16 and the phone's address

- `pm grant <pkg> android.permission.BLUETOOTH_CONNECT`: valid. It is a runtime permission declared by the app, and the shell may grant it; the grant does not kill the app, so `hasPermission` at BtServer.java:366 sees it at once. Confidence high.
- `svc bluetooth enable`: exists; on Android 13+ it forwards to `cmd bluetooth_manager enable`, the same command as the fallback at BtServer.java:371. Field evidence supports it: the old saver's `svc bluetooth disable` did switch this phone's Bluetooth off (HANDOFF.md:28).
- `settings get secure bluetooth_address`: from memory, the settings provider returns it only to callers holding `LOCAL_MAC_ADDRESS`, which the shell package has. Whether Android 16 / One UI still stores the value is not verified.
- The fallback `dumpsys bluetooth_manager | grep -i -m 4 'address'` (BtServer.java:383) will probably give only the hidden form `XX:XX:XX:XX:..` on Android 14+, which `BtAddr.find` rejects (BtAddr.java:25-37). The grep is loose enough to pick up another device's full address (defect 11).
- `BluetoothAdapter.getAddress()` needs `LOCAL_MAC_ADDRESS` (API 36 stub) and returns `02:00:00:00:00:00` to a normal app. The code never calls it, and `BtAddr.valid` rejects that placeholder (BtAddr.java:21).

## 4. Threads and lifetime — verdict: sound, with three real flaws

- **Bluetooth off and on:** handled by the state receiver (BtServer.java:124-137). `RECEIVER_NOT_EXPORTED` is fine because the broadcast comes from the system.
- **No retry after a failed listen:** if `listenUsingInsecureRfcommWithServiceRecord` throws once, the state stays "error" until Bluetooth is toggled or the service restarts (BtServer.java:217-220). Defect 3.
- **Service destroyed:** `stop()` closes the listener, the connections and the timer (BtServer.java:155-177; SenderService.java:210).
- **accept() failing repeatedly:** no busy loop; three quick endings stop the listener (BtServer.java:263-275).
- **Several callers:** each connection has its own thread; at most 2, and a third closes the oldest even mid-request and even if it is the paired tablet (BtServer.java:254-256). Defect 15.
- **Sockets:** closed on every path (BtServer.java:330-341; BtLink.java:177, 187, 253, 325).
- **Wake locks:** none can be held forever; all are timed and not reference-counted (Awake.java:19-33, 47-55).
- **Shared link lock:** it is one static lock, and any connection's `waiting()` or `finally` releases it while another connection is working (BtServer.java:298, 331). Defect 6.
- **Timers:** both executors drop their thread when idle (BtServer.java:279-289; BtLink.java:82-92). They run on a clock that stops in deep sleep, so the phone's 15 s / 90 s silence limits stretch while it sleeps; harmless.
- **Main thread:**
  - `BtServer.start` runs in `onStartCommand` (SenderService.java:72).
  - The receiver calls `listenLocked` (BtServer.java:126-136), which is a blocking binder call plus a blocking read for the channel number.
  - `BtLink.setEnabled(false)` closes a socket on the UI thread (SettingsTab.java:88; HomeTab.java:378).
  - No `Priv.run`, connect or read was found on the main thread in the new code. Defect 12.
- **`Actions.later`:** its thread does not catch RuntimeException (Actions.java:58). Defect 13.

## 5. Security of the link — verdict: sound

- **Signing and replay:** every request needs a rising `X-Seq` and an HMAC over nonce, number, method and target, checked against each trusted secret in constant time (LinkServer.java:76-77; LinkAuth.java:53-84). Three bad requests close the connection (LinkServer.java:81).
- **Routes:** only `RemoteRoutes.BLUETOOTH_PATHS` (RemoteRoutes.java:16-32); `/pair` is excluded; a body ends the connection (LinkServer.java:70-75).
- **`/act` is wider than the doc implies:** it carries every action name over Bluetooth, including `net_lte`, `nrleg_*`, `restart_modem`, `menu_dial`, `restore` (Actions.java:210-313). `docs/REMOTE_LINK.md:60` says band routes are refused; that is true only for `/band*`.
- **Secret:** never written to the connection (LinkClient.java:50-58), and there is no logging in the link classes.
- **What an unauthenticated peer can do:**
  - Hold a connection for 15 s, at most about 45 s with three bad requests.
  - Use at most 8 KB of memory per request head (Http.java:82).
  - Hold the wake lock for microseconds per request (BtServer.java:319, then 298).
  - Evict the tablet's connection by opening two of its own (defect 15) and release the tablet's wake lock (defect 6).
  - It cannot reach a handler.
- **Residual risks:**
  - Replies are not signed, so a device that fakes the phone's address can feed the tablet a false status. The tablet stores `btmac` only from hotspot answers (HomeTab.java:274-279).
  - Over Wi-Fi the secret still travels in clear to whatever host `Net.phoneHost` names, including the gateway of any other Wi-Fi the tablet is on (Net.java:52-88; Call.java:41; Client.java:150-152). This is older behaviour, but the Bluetooth link makes a stolen secret usable without the hotspot password (defect 18).

## 6. Semantics

- **Answer first:** the answer cannot be overtaken by the action.
  - Over the hotspot, `Http.json` flushes before `Actions.later` starts its 800 ms wait (Api.java:172-173; Actions.java:56).
  - Over Bluetooth, the reply is written right after the handler returns (LinkServer.java:96-103), and `Awake.work` holds a separate 90 s lock (Actions.java:53).
  - Residual: a restart or airplane mode 0.8 s later could cut a slow Bluetooth delivery; the tablet would then say "may or may not have done it".
- **Standby** (Actions.java:182-189): runs `hotspot-stop`, then the checked Sleep (`Screen.powerNow`, nodoze). Both need Shizuku.
  - Over the hotspot it is answered first; if the direct stop fails, the airplane-mode blip is used (Actions.java:160-165).
  - Over Bluetooth it acts first and never uses the blip (Actions.java:156-159).
  - Without Shizuku: 409 with "Shizuku is not running" (Api.java:167-170, or Priv.java:74-76 through Api.java:179-181).
- **Restart:** `svc power reboot || reboot` (Actions.java:110); always answered first; Shizuku is checked before the answer.
  - Replay: refused over Bluetooth. Over Wi-Fi there has never been replay protection (secret only).
  - Accident: no tablet screen offers it today; `poweroff` has a confirmation (ControlTab.java:91-93).
  - Double send: `Reach` and `BtLink` never repeat a POST (Reach.java:99-109; BtLink.java:254).
- **Failures reported as success:** `perform()` returns failure sentences as normal text, so the reply is `{"ok":true,"out":"The hotspot could not be switched on: …"}` (Actions.java:78; Api.java:177-178). Defect 7.
- **Waking the phone from standby over Bluetooth — plausible, not verified.** It needs all of:
  1. the phone's Bluetooth on;
  2. the foreground service alive with the permission;
  3. Shizuku alive;
  4. range;
  5. the address known on the tablet;
  6. the unbonded connect working.
  
  From memory: Doze does not block Bluetooth, and a process with a foreground service keeps its wake locks in Doze. The app holds no lock between `accept()` and the first complete request (BtServer.java:319 is the first acquire), so the greeting relies on the Bluetooth stack keeping the CPU up for a moment after the controller wakes it (defect 19).
- **The tablet promises reachability it has not checked:** the Home confirmations say the phone "stays reachable over Bluetooth" on `BtLink.ready()` alone, which covers only item 5 (HomeTab.java:481-488, 508-515; BtLink.java:52-54). Defect 1, the most important finding.

## 7. The tablet side

- **Switching Bluetooth on:** works on API 29, but it is done by the background status poll whenever the hotspot route cannot connect, and it is never switched off again (BtLink.java:122-147 via HomeTab.java:229). Defect 9.
- **Timeouts:** every wait is bounded: connect 14 s, greeting 6 s, request readMs + 3 s (BtLink.java:27-28, 173, 182, 237). Closing the socket from the timer does unblock `connect()` and `read()` on Android 10.
- **One lock for everything:** `BtLink.call` holds the class lock for the whole call (BtLink.java:199), so a button press waits behind a poll that is stuck connecting (up to 9 s enable + 14 s connect).
- **The user's Wi-Fi:** untouched by the code. Bluetooth is used only when the hotspot route cannot connect, so radio sharing matters only on a foreign Wi-Fi or when the phone's service is down.
- **Sending twice:** never for a state change. Only a GET is repeated, and only on a reused connection that turned out dead (BtLink.java:254); every caller sends actions as POST.
- **Timeouts tuned for answer-first:** over Bluetooth the phone acts before answering, so `radio_cycle` (10 s) and `hotspot_restart` (15 s) can time out although the phone did them (BandsTab.java:477; SweepLink.java:81; ControlTab.java:274-276). Defect 8.
- **Polling over Bluetooth every 15 s:** yes, it keeps a standby phone awake needlessly. The poll interval (15 s) is shorter than the reuse limit (25 s), so one connection stays up for as long as the Home tab is open and the phone's CPU is woken each time (HomeTab.java:189-193; BtLink.java:26). Defect 4.
- **Polling chains multiply:** `pollNow()` during a running poll adds a second permanent chain (HomeTab.java:73-86, 196-202, 470-471). Defect 5.
- **Dead button:** `act.openTab("Phone")` finds no tab on the tablet; the tabs are Home, Speed, Bands, Control, Screen, Files, Texts, Settings (HomeTab.java:365; MainActivity.java:227-229, 311-321). v0.2.102 opened "Control". Defect 2.

## 8. hotspot-start / hotspot-stop in ShellMain

- **API 36 shapes (stub checked):** public `startTethering(TetheringRequest, Executor, StartTetheringCallback)` and `stopTethering(TetheringRequest, Executor, StopTetheringCallback)`; `StartTetheringCallback` is an interface with default methods, so the proxy at ShellMain.java:583 is valid. The int forms are hidden system API; from memory they still exist at runtime.
- **Start:** both shapes are tried (ShellMain.java:557-575). A plain request with no hotspot configuration is allowed only for privileged callers; from memory the shell holds `TETHER_PRIVILEGED`. Not verified on Samsung.
- **Neither shape:** "ERR this phone has no startTethering(type or request, executor, callback); switch it on by hand on the phone" (ShellMain.java:577). It reaches the tablet as "The hotspot could not be switched on: …" (Actions.java:78): understandable, with jargon, and flagged ok (defect 7). An exception arrives as "ERR java.lang.…" (ShellMain.java:59-62).
- **Stop:** `call(tm, "stopTethering", 0)` picks the one-argument form (ShellMain.java:636, 222-229). If it is missing, the Java exception name is shown; over the hotspot the airplane blip follows.
- **State:** `IWifiManager.getWifiApEnabledState()` takes no arguments on every version (ShellMain.java:465-472).
  - If the state cannot be read after stopping, the helper answers "OK … was asked to stop" (ShellMain.java:645-647) and the tablet is told "The hotspot is off." unverified.
  - `onTetheringStarted` is taken as "the hotspot is on" (ShellMain.java:588-591), which on Android 15 and older only means the request was accepted (defect 10).
  - State 12 (switching on) is not treated as already starting (ShellMain.java:550).
- **Time budget:** fits. The helper takes at most about 22 s inside `Priv.run(…, 40)` (Cells.java:41), against the tablet's 45 s and the phone's 120 s lock.
- **Unknown:** whether Samsung obeys the AOSP tethering path from the shell, and whether the carrier's entitlement check leaves a started hotspot without internet.

## 9. Anything else

- **Phone-side link state is ignored:** the tablet reads `bt` only as "the phone's app is new enough" (HomeTab.java:366). The Settings chip says "Ready" as soon as an address is stored, even right after a failed test (SettingsTab.java:102-106, 177-179). Part of defect 1.
- **Hotspot state on Android 16:** `Radios.hotspotOn` tries the hidden `isWifiApEnabled`, then the sticky broadcast, then interface names (Radios.java:25-49; Boot.java:105-123). From memory the broadcast is no longer sticky from Android 12L. If all three miss, `hs` is false while the hotspot is on, and Home's button reads "Hotspot on" so the hotspot cannot be switched off from Home (HomeTab.java:431-433). Not verified.
- **Bluetooth requests do not touch the saver or the stored tablet address** (Api.java:53-56); intended.
- **Doc drift:**
  - `docs/REMOTE_LINK.md:42` says the wake lock is 30 s; the code uses 120 s (BtServer.java:319).
  - HANDOFF says 1,503 checks; the suite now runs 1,525.
  - ControlTab's "Hotspot off" text still says "To turn it back on, use the phone." (ControlTab.java:84-86).
- **Test coverage:** BtServer, BtLink and Awake have none; the shared-lock flaw (defect 6) is outside what `LinkProtocolTest` models.

## Not knowable from here

1. Whether the unbonded connect works without a dialog on One UI / Android 16 and on the MT8168.
2. Whether the address can be read on Android 16.
3. Whether Samsung obeys `startTethering` / `stopTethering` from the shell, and the entitlement behaviour.
4. Whether `hs` is right on Android 16.
5. Whether the greeting gets out when the phone was in deep sleep.
6. Whether Samsung keeps the service and Shizuku alive through hours of standby.
7. Range through walls.