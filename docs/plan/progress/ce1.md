# CE1 (user request 2026-11-15, Launcher TASKS.md CE1): Fast Wi-Fi vs the Launcher's VPN-off

User words: "Never automatically turn off fast wifi mode if I turned it on and I'm inside quickstep launcher. Turn off the fast wifi if necessary if I go to the lock screen so that it doesn't show up in the notification panel. The VPN bar in the notification panel should never show up at any time on any lock screen or in custom launcher. If I had fast wifi turned on then go to the lock screen, then go back to quickstep launcher, automatically turn back on fast wifi mode in linkmeter if possible."

Cause: v0.2.183 put the VPN back 2 s after every revoke; Launcher VpnOff (CB2) revokes at screen off / lock screen; after 3 stops the Launcher gave up -> row on the lock screen.

Design: Launcher (tjshea90/Launcher, same branch name ccr-9028296e-kjveqx) publishes Settings.Global `tj_launcher_world` = `quickstep` | `quiet:<why>` (written with its adb grant; readable by anyone). LinkMeter (tablet) PAUSES its VPN (state PAUSED, choice kept) while: screen off, keyguard locked, or the word is quiet; never puts the VPN back while paused; resumes by itself (receiver SCREEN_ON/USER_PRESENT + ContentObserver on the word + 30 s level look) when the world is an unlocked Quickstep. No word = fail-open (own screen/keyguard facts only).

DONE (Launcher side, committed ckpt 1358): VpnOffRules.why(quietWorld...), worldValue/isQuiet, VpnOff.sync inside AnimationSpeedup.syncNow, TjAccessibilityService call sites, Control text, tests.
TODO here: FastRules.pauseWhy/pausedWords/PAUSED card, FastVpn gate (attempt first, onRevoke, resumeIfWanted), tile words, HISTORY 10, FastRulesTest, FastPreviewTest scenarios, mutate_fast (pure) + glue breakages, docs/FAST_WIFI.md, HANDOFF.md, USER_GUIDE.
