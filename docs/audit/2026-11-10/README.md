# Audit of 2026-11-10 (second session of "Remote power + polish")

Six read-only analysts read the whole app against the user's request and against the field evidence in `HANDOFF.md`.
Nothing here was verified on a device, and a finding is one analyst's reading: check it against the code before fixing it.
Each area has three files: `<area>.report.md` (the map, with file:line), `<area>.defects.md` (numbered D1, D2, ...:
severity/confidence, where, what, fix) and `<area>.decisions.md` (design questions with a recommendation, then the files an
implementer would touch). Line numbers are those of commit `62b05a4`.

| area | what it covers |
|---|---|
| `bluetooth-glue` | defect hunt in the Bluetooth link and the remote switches (never run on a device) |
| `battery` | the battery saver end to end, plan step C, a power audit of both roles |
| `control-tab` | the tablet's Control tab, to become "Phone": inventory, duplicates, proposed layout |
| `signal-tab` | the Bands tab, to become "Signal", and the removal of what the modem does not obey (plan E) |
| `phone-screen` | the phone's own screen, the Bluetooth controls it lacks, the safety rows that are missing |
| `settings-contradictions` | every stored setting, dead or misleading controls, contradictions between screens |

Which finding was fixed in which checkpoint, and which were left on purpose, is in `HANDOFF.md` (newest block, "THE TRACKS").
