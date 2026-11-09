# Upload ideas: other ways to help a weak 5G UW uplink without root (researched 2026-11-09)

**Status (2026-11-09, later): ideas 1 (the 5G leg), 3 (hidden-menu probe) and a fairer comparison are BUILT and tested offline, waiting for CI and the user's field test (details: HANDOFF.md, top block). A placement helper ("find the best spot": live score of the 4G anchor, beep, marked spots) is built too. Still NOT built: the 24 h link recorder (idea 2), the reroll (idea 4), the time-of-day policy (idea 5).** The user said that everyone in their brick building gets 0.1 to 1 Mbit/s by day and 4 to 8 at night, and that hotspot off did not help: so weak indoor RF times daytime load; physical levers (idea 6) are the biggest. Keep this file true.

The user's question: "If my modem won't accept forced band requests, is there any other way to force the band or speed
up or strengthen the cell connection from the software without root? Think outside the box. My problem is extremely slow,
almost 0 upload speeds during the day with a weak 5G UW signal."

## What is established, and how sure

| Claim | Sure? | Basis |
|---|---|---|
| `setSystemSelectionChannels` is a hint for which bands the modem's background SCAN acts on (made for CBRS), not a "stay on this band while connected" lock. "Acknowledged but not obeyed" is therefore normal. | fairly (wording is documented; this modem's behaviour is observed) | AOSP framework/HAL commit text and CTS (they test it with CBRS channels); HANDOFF's own field data (every lock NOT obeyed) |
| v0.2.71 makes the phone search again after a lock and waits for it to confirm. If a CONFIRMED search still lands on other bands, locks are dead on this firmware. | n/a (not yet field-tested) | HANDOFF 2026-11-08 field-fix block |
| Verizon says uploads can run over 5G and LTE in weak signal. In NSA (EN-DC) the LTE leg gets uplink power priority, and a weak NR leg can lose power or fail. | plausible, NOT measured on this phone | Verizon network-performance page; patents and vendor white papers |
| This phone (SM-S156V, MT6835 = Dimensity 6100+, sub-6 only) has no mmWave, so its "5G UW" can only be C-band n77. | inferred | third-party spec lists show n77 and no n260/n261; the Bands tab's carrier list shows the real NR band |
| Total Wireless is a Verizon prepaid brand; several comparison sites say lower tiers are deprioritized behind Verizon postpaid in congestion; sources conflict on which tiers. "Slow only during the day" fits congestion/priority as well as it fits RF. | unverified for the user's plan | comparison sites; check the plan page |
| The upload test is real: 2 streams, 64 KB send buffer (`SpeedTest.transfer`, `SmallBufferFactory`). One 8 s sample varies 2.6 to 7.5 Mbit/s (noise). | yes | code |
| Samsung `*#2263#` is reportedly blocked on Verizon firmware ("RAT selection option restricted"). | forum reports only | forums |
| `cmd phone cc set-value` is reportedly root + non-user-build only; carrier config cannot choose RF bands anyway. | one source-reading blog | blog |
| Phones may cut cellular transmit power while the hotspot is on (SAR), and when hot. | one FCC filing for another phone; unverified for the A15 | FCC filing; general knowledge |

## Ideas, best first (what, why, status)

1. **NR leg off, 5G still allowed**: `TelephonyManager.setNrDualConnectivityState(DISABLE)` (system API, needs
   MODIFY_PHONE_STATE which the shell has; only works if the modem reports `CAPABILITY_NR_DUAL_CONNECTIVITY_CONFIGURATION_AVAILABLE`;
   `DISABLE` waits for the bearer to be released, `DISABLE_IMMEDIATE` drops the NR cell at once and may cause a radio link failure).
   Keeps the strong LTE anchor and removes the weak NR leg that shares uplink power. "Force 4G" (works, measured 4.2 vs 2.6 Mbit/s
   but noisy) is the blunt version of this. NOT built. Must be undone by Restore everything / Guard.
2. **24 h link recorder**: log band, RSRP, RSRQ, SINR, NR on/off, 5G UW flag, battery temperature, thermal status and a tiny
   upload probe (about 100 KB every 15 min is about 10 MB/day) with a time-of-day chart. It tells apart: congestion or priority
   (RSRQ and SINR fine, speed collapses at peak hours), RF (SINR poor all day), heat (collapse when hot), hotspot penalty (probe with
   the hotspot on and off). Today only battery temperature (`Status.tmp`), RSRQ and SINR exist, and only as a snapshot. NOT built.
   The phone's "no timers" rule is a deliberate design choice; the recorder must be an opt-in exception.
3. **Hidden-menu probe**: add to the Probe report `pm list packages | grep -iE 'engineer|servicemode|rilservice|mediatek'`,
   `cmd phone cc get-value` (read-only check whether `cc` is allowed), `dumpsys carrier_config | head`. The shell may start non-exported
   activities (START_ANY_ACTIVITY), so a menu that exists can be opened with `am start -n` and seen through the Screen tab.
   Expected: Samsung/Verizon removed or locked them. Costs minutes, no risk. NOT built.
4. **Re-attach "reroll"**: loop `radio_cycle` (exists, v0.2.71, unverified) until the anchor band/SINR meets a target, at most 6
   tries, 60 s apart. Each try takes the hotspot down for about 30 to 60 s. At a fixed spot the camp choice is mostly the same each
   time, so odds are modest. Never use airplane mode for this (it kills the hotspot and the tablet link).
5. **Time-of-day policy** (needs 2): choose LTE-only or 5G by what was measured at that hour, with hysteresis.
6. **Physical and carrier levers** (the biggest honest ones): window or upper floor, case off, phone not in the hand or pocket,
   keep it cool; an inductive-cradle booster or a hotspot/router with external antenna ports (many have a working band-lock page);
   check the Total Wireless plan's priority tier; run big uploads at night.
7. **Do not waste time on**: APN edits (the network sets QoS from the subscription), TTL tricks (the phone's own upload is also
   slow, so tethering rules are not the cause), "signal booster" apps, DNS/MTU changes.

## Proposed builds (each: pure logic plus a test in `tools/test.sh`, trace route to reply end to end)

- **A** NR-leg switch: `ShellMain nr-dc get|off|on` by reflection (read the constant values from the phone's own class, do not hard-code),
  an action pair in `Actions`/`Api`, one more row in `SweepPlan` so "Find the best upload" can compare it, `RadioState` check that the NR carrier
  really disappeared, Restore everything puts it back to ENABLE.
- **C** Probe additions (idea 3). Smallest, do first.
- **B** Link recorder (idea 2): sampler in `SenderService` (opt-in), ring file, `/rec/*` routes in `Api.handle` AND the router switch,
  pure `RecChart` on the tablet.
- **D** Reroll (idea 4) on top of `radio_cycle` and `radio_cycle_state`.

## 10-minute tests for the user (no code needed)

1. Update both apps (v0.2.75 or newer), Bands > "Find the best upload" with "search again after each lock". The summary line either says
   "even after searching again" (locks are dead, stop) or shows a lock that was obeyed.
2. Same spot, at the bad time of day: 5G allowed vs Control > Force 4G, at least 3 upload-only runs each (one run is +/- 3 Mbit/s).
3. A speed-test app on the phone itself with the hotspot off, then on. A big gap means a hotspot penalty.
4. The same test at night. If night is fine, it is load or priority and no band choice fixes the daytime.
5. Move the phone while watching the Home meters (window, higher up, case off). Note battery temperature (hot phone = suspect).

## Sources

- Verizon, network performance: https://www.verizon.com/about/our-company/network-performance
- AOSP mirrors for `setSystemSelectionChannels` and the CTS change: https://gerrit.omnirom.org/plugins/gitiles/android_hardware_interfaces/+/1601f913b96a72f6e2ea0bcc70b17d38810b2df9%5E2 and https://android.googlesource.com/platform/cts/+/1e688af8088%5E%21/
- `setNrDualConnectivityState` (AOSP mirror): https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/60310d719e12ec1a30bc85be24e1d695f844bd13
- NSA uplink power sharing (patents): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11546861 and https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/10687287
- Samsung `*#2263#` on carrier firmware: https://www.howardforums.com/threads/2263-gone.1928818/
- Hotspot power reduction in an FCC filing (another phone): https://fcc.report/FCC-ID/PY7A8880001/1700208.pdf
- Total Wireless priority (comparison sites, conflicting): https://www.upphone.com/carriers/total/ and https://www.rvmobileinternet.com/gear/total-wireless/
- `cmd phone cc` restriction (blog): https://lucidusdev.github.io/2024/02/01/change-carrier-name-in-android-with-root
