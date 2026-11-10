# Track T1, part a: LINK CORRECTNESS ON THE PHONE (no screens)

Scratch folder: the one named in your task (never inside the repository).

Goal: the Bluetooth link and the remote switches, as the PHONE runs them, are correct and honest before anybody builds more
screens on them. Read `docs/REMOTE_LINK.md` first, then `docs/audit/2026-11-10/bluetooth-glue.report.md`, then the findings
below in their `.defects.md` files. Part b (another implementer, after you) does the tablet side: do not change `BtLink`,
`Call`, `Reach`, `HomeTab`, `SettingsTab` or other screens except where a phone-side change forces a one-line adjustment.

## Findings to act on (verify each first)

From `bluetooth-glue.defects.md`:
- D3: the listener never retries after "error" or "perm". Add the cheap self-heal (`BtServer.poke()` or the name you prefer):
  at most once per 30 s, on a background thread, reached from the status the tablet polls.
- D6 and D19: the link wake lock. Count the holders so one connection cannot release it under another, and hold it briefly
  from `accept()` until the greeting is out. Every hold still runs out by itself.
- D7 (decision 5 in HANDOFF): a switch that did not move must answer as an ERROR. In the act-then-answer path a plain failure
  (hotspot on / off / restart not done, Wi-Fi not switched, screen did not go off) becomes an `IOException` with the sentence,
  which `Api.act` already answers as 409 `{"err": ...}`. The answer-first path (`Actions.later`) stays as it is. Standby has
  two parts: decide and say what the answer is when one part failed. Then check every place on the tablet that reads the
  reply (`Call.run` and its callers) still shows the sentence; adjust one line there if it does not.
- D10: `hotspot-start` must not say "on" before it is on: after the callback, when the state can be read, wait (bounded) for
  "enabled"; treat "enabling" at entry as "wait for it"; `hotspot-stop` answers a distinct line when the state cannot be read,
  and the action's sentence then says so instead of "The hotspot is off."
- D11 (phone part): the address fallback must only accept the adapter's OWN address line.
- D12 (phone part): `BtServer.start` and the state receiver's work off the main thread.
- D13 and `battery.defects.md` D16: `Actions.later` catches `RuntimeException` (recorded the same way) and releases the work
  wake lock when the action has finished (`Awake.workDone()` in a `finally`).
- D14 (phone part): reset the cached Bluetooth name when `bt_setup` has just given the permission.
- D15: when a connection must be closed to make room, close one that has not proved itself before one that has.
- D17: bring `docs/REMOTE_LINK.md` into line with the code (the wake lock's real length; which actions really travel over
  Bluetooth: `/act` carries every action name there).

From `phone-screen.defects.md`:
- D1: switching the phone-side link off (`Cfg.btLink` false) must also close the connections that are open.
- D2: a `BtServer.recheck()` (or the name you prefer) that listens again only while the service wants the link; `start()` must
  not leave a listener behind when the service is not running. Part T6 will call it from the phone's screen.
- D3: `bt_setup` does nothing and says so when the phone-side switch is off.
- D15: `BtServer.served` / `lastAt` are written and never read: give them a reader (a short text such as "last asked over
  Bluetooth at 14:02", which T6 will show) or delete them.

From `control-tab.defects.md` and `settings-contradictions.defects.md`:
- control D9 / settings D7 (decision 6 in HANDOFF): `/screen/state`, `/screen/on` and `/screen/off` are power switches and
  pass the phone's "Let the tablet see and control this screen" switch; picture, touches and typing stay behind it.
- control D12: "Prepare phone" must say honestly whether its commands worked.
- control D17: `restart_modem` over the hotspot. Read what it really does to the hotspot, then decide: answer first on the
  Wi-Fi route (and keep `RemoteRoutes` and its tests and `tools/mutate_link.py` in step), or leave it and say why.
- settings D6 / control D11: `hotspot-restart` waits for the hotspot to be really off before starting it again and answers
  an error if it never went off; `hotspot-band` reads the band back and says "the phone kept ..." when it differs.

One more, from `bluetooth-glue.report.md` section 9 ("Hotspot state on Android 16"):
- HS: `Radios.hotspotOn` may answer "off" while the hotspot is on if none of its three sources works on Android 16 (the
  broadcast is probably no longer sticky). Make the answer three-valued inside the phone (on / off / not known): add the
  cheapest reliable source you can find without Shizuku (for example remembering the last `WIFI_AP_STATE_CHANGED` the service
  saw; check what a normal app may receive on Android 16), and when nothing can tell, leave `hs` OUT of the status instead of
  saying false. Check what `RemoteRoutes.cutsLink(..., hotspotOn)` and `Actions` do with "not known" and choose the safe side
  (answer first). Note for part b in your answer what the tablet should show when `hs` is missing.

## Tests

- `tools/test.sh` must pass; `python3 tools/mutate_link.py` and `python3 tools/mutate_link.py reach:` must still catch all
  their breakages (run them; extend them if you add a rule to a class they cover).
- New pure rules (for example the holder count, "which connection to close", the three-valued hotspot answer if it has
  logic) get a small offline test, registered in `tools/test.sh`.

## When you are done

Save (see COMMON). In HANDOFF's track list, on the T1 line, write "phone side done: ..." (do not tick the box: part b follows).
In your answer list, for part b: every reply shape you changed (fields, codes, sentences the tablet may want to match).
