#!/usr/bin/env bash
# All the offline tests: the phone-settings decisions, the H.264 header fix, the H.264 picture cutter and the release
# pipeline's safety gate.
# (The rest of the app can only be compiled by GitHub Actions; there is no Android SDK here.)
# Exits non-zero if anything fails: the release pipeline uses that to decide whether a build is good.
set -u -o pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0
out=$(mktemp -d)
echo "== ProfilePlan (the phone's remote-session settings)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/ProfilePlan.java tools/test/ProfilePlanTest.java || fail=1
java -cp "$out" com.tj.linkmeter.ProfilePlanTest || fail=1
echo "== SpsFix (the H.264 header fix, against real x264 streams and ffmpeg)"
tools/test-spsfix.sh || fail=1
echo "== AnnexBFramer (cuts the phone's H.264 stream into whole pictures, against real x264 streams and ffprobe)"
tools/test-framer.sh || fail=1
echo "== Release decision (the pipeline's safety gate, taken from .github/workflows/build.yml)"
python3 tools/test/test_release_decision.py || fail=1
if [ "$fail" -ne 0 ]; then
  echo "OFFLINE TESTS FAILED"
fi
exit "$fail"
