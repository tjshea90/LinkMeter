# fastkeep: Fast Wi-Fi must stay on until the user turns it off
STATUS: WORKING (session 2026-11-15, branch ccr-4c300f11-vgy844)
User's words: "For some reason the fast wifi toggle keeps turning off by itself. If I turn on fast wifi, make it stay on until I turn it off."

FOUND BY READING THE CODE (not seen on a device): the stored choice `Cfg.fastWanted` is cleared by FOUR things only:
 (1) the user: Home card "Hotspot", the tile while on, the notification's "Turn off";
 (2) Android's VPN question answered Cancel (MainActivity REQ_VPN);
 (3) `FastVpn.onRevoke()` - Android takes the VPN away (another VPN app became active, Android's VPN settings, consent lost). It cleared the choice AND stopped the service: this is the "by itself". Nothing brought it back.
 (4) also: wanted=true but the service not running (app stopped/force-stopped) showed "starting..." forever; nothing restarted it except BootReceiver.
PLAN: only (1) clears it. onRevoke keeps the choice, tears the VPN down, puts it back when Android lets us (VpnService.prepare()==null), else card says Android needs the OK (state PERMISSION with the reason). 3 revokes in 10 min = stop fighting another VPN app, ask. `FastVpn.resumeIfWanted` (MainActivity.onResume, FastCard.onShow, tile) restarts a service that is gone. Cancel of Android's question re-checks consent first. A short history of what changed it (persisted, Settings > Fast Wi-Fi) so the next report says WHY.
FILES: FastRules (afterRevoke, remember, PERMISSION_LINE), FastVpn, FastCard, FastTile, MainActivity, Cfg, SettingsTab, tests FastRulesTest + tools/mutate_fast.py + FastPreviewTest, docs/FAST_WIFI.md, HANDOFF.md.
DONE: nothing built yet.
