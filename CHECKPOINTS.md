# Checkpoints

Newest at the bottom. `tools/checkpoint.sh "message"` appends a line here, commits and pushes.

- 2026-11-08 initial project written (sources, workflow, handoff). Never compiled yet.
- 2026-11-08T00:32:45Z stage 1: token pairing, boot-start service, tabbed tablet UI
- 2026-11-08T00:37:26Z stage 2: Shizuku runner, control tab, power actions, 4G toggle, battery saver, probe
- 2026-11-08T00:41:09Z stage 3: speed test on the phone with gauge screen on the tablet
- 2026-11-08T00:45:30Z stage 4: file manager in both directions, phone screen moved into tabs
- 2026-11-08T00:50:25Z stage 5: cell survey, experimental band lock via ShellMain, 4G toggle through the telephony API
- 2026-11-08T00:52:46Z handoff rewrite, sturdier Shizuku newProcess lookup
- 2026-11-08T01:13:38Z fix: don't trust the Wi-Fi manager to say the tablet is on Wi-Fi; show the phone address and what reaches the phone
- 2026-11-08T01:15:33Z handoff: record the pairing issue, diagnostics and sandbox limits
- 2026-11-08T01:28:44Z 4G toggle reads and writes the word form this phone uses; cell scan uses the serving channel; record probe results
- 2026-11-08T01:39:53Z ShellMain: set up the telephony service wrapper first (fixes NullPointerException); show API route errors in 4G result
- 2026-11-08T01:46:33Z speed test: use the active mobile network, fall back to the default network, name the failing stage
- 2026-11-08T01:53:44Z speed test: small upload send buffer and two streams so upload shows the real speed, not buffer fill
- 2026-11-08T02:03:18Z update the phone app from the tablet over the hotspot; automatic version numbers
- 2026-11-08T02:04:49Z handoff: document tablet-driven phone updates
- 2026-11-08T02:06:52Z hotspot Wi-Fi band chooser (experimental) and restart; docs for tablet-driven updates
- 2026-11-08T02:10:01Z Restore everything (phone, tablet, notification), automatic undo if data stops working, saved originals
- 2026-11-08T02:23:59Z restore: leave network types alone when LinkMeter hasn't changed them and they already look normal
