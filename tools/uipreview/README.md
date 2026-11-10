# Looking at the app's screens without a device

`tools/preview.sh` draws every tab of the tablet app and the phone's own screen to PNG files in `tools/uipreview/out/`
(600 x 973 pixels at 170 dpi with font scale 1.2: the user's tablet). Open the PNGs to see what a change looks like.

How: this folder is a small separate Gradle project. It compiles the app's real sources (`app/src/main`) with
[Robolectric](https://robolectric.org) in its "native graphics" mode, starts `MainActivity` inside a normal Java process,
switches from tab to tab and draws each window into a bitmap. Nothing here is part of the app or of the release pipeline:
the app itself still has no third-party library.

Limits: no phone answers, so every screen shows its "cannot reach the phone" state unless the test fills views in by hand;
video surfaces are black; system dialogs are not drawn. `PreviewTest.java` is the place to add a screen or a state.

Needs: the Android SDK (in the cloud sandbox: `/opt/android-sdk`) and network access to Google's Maven mirror for the first run.
