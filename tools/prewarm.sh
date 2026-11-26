#!/bin/sh
# Warms what the first build and the first screen preview of a session would otherwise download. The setup script of the cloud environment
# runs this in a freshly cloned LinkMeter (HANDOFF.md, "SETUP SCRIPT"); it can also be run by hand. Two independent parts: a failure of one
# never stops the other, and nothing here may stop a session from starting (the setup script only prints a line when this exits non-zero).
# Safe to run again: everything is cached.
#   APP       Gradle itself, the build plugins, the app's libraries and the build cache: what ./gradlew assembleRelease needs (about 1 minute)
#   PREVIEWS  the preview project's libraries and Robolectric's Android 10 and Android 15 files (about 300 MB), by running two small tests
#             (about 2 minutes): what tools/preview.sh needs. If Maven Central refuses (HTTP 429), this part fails and the first preview run
#             of a session downloads what is missing, as it always did.
# Switch a part off by setting it to 0 here.
PREWARM_APP=1
PREWARM_PREVIEWS=1

cd "$(dirname "$0")/.." || exit 1
: "${ANDROID_HOME:=/opt/android-sdk}"
export ANDROID_HOME
bad=0
if [ "$PREWARM_APP" = 1 ]; then
  echo "== app: ./gradlew assembleRelease"
  timeout 900 sh ./gradlew --no-daemon -q assembleRelease || { echo "app warm-up failed"; bad=1; }
fi
if [ "$PREWARM_PREVIEWS" = 1 ]; then
  echo "== previews: one small test for each Android version (10 and 15)"
  timeout 900 bash tools/preview.sh \
    --tests "com.tj.linkmeter.FloatingExtrasPreviewTest.recentsAndContactsShowThePhonesCallsAndPeople" \
    --tests "com.tj.linkmeter.AndroidSaverPreviewTest.phoneScreenSaysBothSavers" > /dev/null || { echo "preview warm-up failed"; bad=1; }
fi
echo "warm-up done, problems: $bad"
exit $bad
