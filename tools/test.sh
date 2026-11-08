#!/usr/bin/env bash
# All the offline tests: the phone-settings decisions and the H.264 header fix.
# (The rest of the app can only be compiled by GitHub Actions; there is no Android SDK here.)
cd "$(dirname "$0")/.." || exit 1
out=$(mktemp -d)
echo "== ProfilePlan (the phone's remote-session settings)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/ProfilePlan.java tools/test/ProfilePlanTest.java 2>&1 | grep -v "Picked up" 
java -cp "$out" com.tj.linkmeter.ProfilePlanTest 2>&1 | grep -v "Picked up" || exit 1
echo "== SpsFix (the H.264 header fix, against real x264 streams and ffmpeg)"
tools/test-spsfix.sh 2>&1 | tail -3
