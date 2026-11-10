# Looking at the app's screens without a device

`tools/preview.sh` draws every tab of the tablet app and the phone's own screen to PNG files in `tools/uipreview/out/`
(600 x 973 pixels at 170 dpi with font scale 1.2: the user's tablet). Open the PNGs to see what a change looks like.

How: this folder is a small separate Gradle project. It compiles the app's real sources (`app/src/main`) with
[Robolectric](https://robolectric.org) in its "native graphics" mode, starts `MainActivity` inside a normal Java process,
switches from tab to tab and draws each window into a bitmap. Nothing here is part of the app or of the release pipeline:
the app itself still has no third-party library.

A pretend phone stands behind the screens (`PreviewTest.Pretend`: which route reaches it, what its status says, what a
switch answers, a Bluetooth call that is slow or fails), so a screen can be drawn in its real states. Helpers in
`PreviewTest.java`, which is the place to add a screen or a state: `press(activity, "label")` presses a button as a finger
would, `dialogShot(name)` draws the question that is open on top of the screen, `later(ms)` lets time pass (the pretend clock
stands still by itself, so anything a screen does "in a few seconds" only happens when the test moves the clock).

Limits: video surfaces are black; Android's own screens (settings, the permission question) are not drawn.

Needs: the Android SDK (in the cloud sandbox: `/opt/android-sdk`) and network access to Google's Maven mirror for the first run.
