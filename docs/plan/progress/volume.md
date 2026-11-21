# Headset volume from the tablet (session branch ccr-9009fd1b-byp19n, started 2026-11-21)

## The user's words (verbatim)
"Add volume options to the app so I can change the volume of my bluetooth from my tablet when it is connected to the phone .
Put the volume settings in both the app on the phone tab and on the accessibility phone floating widget"

## Decisions (do not re-derive)
- "phone tab" = the tablet's Phone tab (ControlTab, title "Phone"). "floating widget" = MiniPhoneView hosted by FloatingPhoneController
  (accessibility host FloatingPhoneAccess on the Go tablet).
- ONE data path for both screens: the live picture `Calls.Now` (GET /call/now, long poll) gets `volume`; ONE new route
  `POST /call/volume?stream=media|call&by=-3..3` (relative steps; no absolute target = no race on quick taps, no jump to full loudness).
  Gated like every /call/* route by the phone's switch "Let the tablet make and answer calls" (hotspot only, like calls).
- Phone side (new `HeadsetVolume`, Android AudioManager): STREAM_MUSIC = media (A2DP absolute volume), STREAM_VOICE_CALL = call volume.
  NEVER touches the phone's own speaker/earpiece by surprise: media moves only when a Bluetooth audio output (A2DP/BLE) is on the phone,
  call moves only while a call is on and its sound is on the headset (or not known with a headset connected). Everything read back;
  Android may refuse to go louder (safe-volume prompt on the phone) -> say so, never claim a change that did not happen.
- Pure rules in `VolumeRules` (tools/test/VolumeRulesTest.java): clamp, which stream by default, enabled/why, words, plan + verify.
- UI: `VolumeView` (row: speaker-minus button, segmented bar, speaker-plus button, "7 of 15"); Phone tab card "Headset volume" (both rows);
  MiniPhoneView: one row (chip Media/Call that switches stream; default Call while a call is up, else Media).
- Volume changes of the phone's own keys reach the tablet live: phone registers VOLUME_CHANGED_ACTION -> fire().

## Plan (checkpoint order)
- [x] V1 Calls.Volume + VolumeRules + CallJson + CallApi route + tests (VolumeRulesTest, CallApiTest, tools/test.sh)
- [x] V2 HeadsetVolume + CallBackend (phone)
- [x] V3 RemoteCalls.volume + CallWatch.volume
- [x] V4 VolumeView + VolumeBarView + Phone tab card + MiniPhoneView row + controller wiring
- [x] V5 PretendCalls + VolumePreviewTest + pictures looked at; docs (PHONE_CALLS, FLOATING_PHONE, USER_GUIDE, SAFETY), HANDOFF block, tools/mutate_volume.py (30 caught)
- [ ] V6 regression run of FloatingPreviewTest + CallsPreviewTest + PreviewTest (was running), tools/test.sh, release build, then checkpoint
      (NO_RELEASE=1 for a finished step; plain `tools/checkpoint.sh` for the closing save = release), then check the run + the release

## Status
Everything is written and compiles (release build green), 59k offline checks green, VolumePreviewTest 5 green. Left: the regression preview run, the final checkpoint and the release check. WIP commit 4793614 has the phone side; the rest is in the working tree if the session dies: commit it with `WIP=1 tools/checkpoint.sh "..."`.
