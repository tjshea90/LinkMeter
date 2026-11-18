# The Launcher bridge ("pocket return") — what it is, who may call it, what it does

The user (2026-11-18, TASKS CH1 of the Launcher repo, verbatim in that repo): when the Launcher app on the SAME tablet returns to its custom launcher from the
"Back to custom" tile or drawer icon (a switch there, default OFF), it must "use the linkmeter app to" put the linked phone to rest:
turn off the phone's hotspot and Wi-Fi, turn airplane mode on, make sure the phone's battery saver is on and its screen is off.
Design of both halves: Launcher repo `research/CH1_POCKET_RETURN_DESIGN_2026-11-18.md`. THIS file is LinkMeter's half. NOT RUN ON A DEVICE.

## The door
`LauncherBridge` (a ContentProvider, authority `com.tj.linkmeter.launcher`, exported). Only `call()` is served. Every call first checks the caller
(`LauncherIdentity`, pure): `Binder.getCallingUid()` must own the package `com.tj.launcher` and that package's signing certificate SHA-256 must be the pinned
`0D:86:C7:...:E5:F4` (the line in the Launcher repo's `tools/keystore.fingerprint`). This app's own key is public (a debug keystore kept in the repo), so only the
Launcher's private key makes the door safe; the claimed package name of a client is not trusted. A debug build of this app (FLAG_DEBUGGABLE) accepts the package
name alone (a debug Launcher has another key). If the Launcher's keystore ever changes, change `LauncherIdentity.PINNED` too, or the bridge answers `refused`.

| call | answer (Bundle) |
| :- | :- |
| `hello` | `ok`, `version` (this app's versionCode), `tablet`, `linked`, `why` (why a run cannot start, "" if it can) |
| `pocket_start` | `ok`, `state`, `sent`, `text`, `startedAt`, `endedAt`, `already` (starts ONE job on its own thread and answers at once) |
| `pocket_state` | the same without starting anything. `state`: IDLE / RUNNING / SENT / DONE / FAILED; `sent` = the phone's sleep order was accepted |

## The job (`PocketRules`, pure and tested in `tools/test/PocketRulesTest.java`; `PocketJob` = the real phone behind it)
1. `wifi_off` — the phone's own Wi-Fi.  2. `eco_auto_on` — LinkMeter's saver.  3. `hotspot_off_deep` — the existing deep sleep (hotspot off, location off, airplane mode on ONLY
while Bluetooth stays up, screen off; `DeepSleepRules` keeps its own rule that the phone is never left in airplane mode with no way to wake it). 4. wait (at most 100 s, a look every
3 s) until the phone's status shows hotspot off + airplane on + screen off, or the phone's own words (`dres`) say how the sleep ended.  5. `eco_android_on` — Android's own Battery
saver, only NOW (SystemSaver puts it back if the hotspot goes down with it; the hotspot is already down).  6. `screen_off` once more if the screen is not off.
Only existing `/act` names are used: the phone app needs no change (an older phone answers "unknown action" in the text).

Rules: a phone that cannot be reached at all ends the run at once; a failing step never stops the next; the tablet's Wi-Fi may go off when `sent` turns true (the hotspot is
the way to the phone until the sleep order is accepted; Bluetooth carries steps 4-6); "done" only when every answer was ok AND the status showed the end state. Requests are sent
as "not pressed": a tablet whose Bluetooth is off keeps it off, and the run then says the phone could not be reached. A partial wake lock (200 s at most) keeps the tablet awake
while the phone goes to sleep. The last result is kept in `pocket_job` preferences and survives a restart of this app (a run that was cut off reads as FAILED).

## Side effects worth knowing
* `eco_android_on` stores the option "Also switch on Android's Battery saver" (Phone tab > Battery) as ON: it stays on until the user presses "Leave alone". SystemSaver's own guard
  still switches it off again if this phone loses its hotspot with it.
* The phone ends in airplane mode with its screen off; "Hotspot on" on the tablet's Home wakes it again over Bluetooth (the existing way).

## Tests
`tools/test.sh` (PocketRulesTest: 35,580 checks incl. every combination of answers against simulated phones and negative controls done by hand: refusing the pin, the order, the
bounded wait, the release of the tablet's Wi-Fi), `./gradlew --no-daemon assembleRelease` (compiles the glue). NOT proven: the real provider call from the real Launcher, the
real phone's behaviour.
