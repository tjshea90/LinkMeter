STATUS: DONE
TASK: Fast Wi-Fi (docs/FAST_WIFI.md), started from commit 94cf07e on branch ccr-2d14c7bc-moxme2
DONE SO FAR: stage 1 RELEASED v0.2.173 (commit 40ea7bc, run 173 green): relay on the phone (FastRelay/FastRelayCore, port 8767),
  VPN engine on the tablet (FastVpn/TunStack), Home card Hotspot | Fast Wi-Fi, quick settings tile, notification Turn off, auto start,
  phone screen card, Settings QUIC choice. Stage 2 RELEASED v0.2.174 (commit 2bad619, run 174 green): phone Wi-Fi Direct group
  DIRECT-LM-LinkMeter 2.4 GHz (FastDirect, fast_direct_on/off, hotspot buttons end it), tablet Settings link choice, TabletDirect
  suggestion, card switching, joining banner. Checks: tools/test.sh green; tools/fast_e2e.sh 22/22; preview 173/173;
  tools/mutate_fast.py 26/26 pure + 11/11 e2e caught.
IN PROGRESS NOW: nothing.
NEXT: wait for the user's report from the devices (speed in both modes, apps, VPN question, tile, battery; stage 2 joining).
LEARNED: see docs/FAST_WIFI.md "NOT KNOWN UNTIL THE DEVICES TRY".
