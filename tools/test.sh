#!/usr/bin/env bash
# All the offline tests: the phone-settings decisions, the Sleep / Wake button rules and steps, the H.264 header fix, the H.264
# picture cutter and the release pipeline's safety gate.
# (The rest of the app can only be compiled by GitHub Actions; there is no Android SDK here.)
# Exits non-zero if anything fails: the release pipeline uses that to decide whether a build is good.
set -u -o pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0
out=$(mktemp -d)
echo "== ProfilePlan (the phone's remote-session settings)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/ProfilePlan.java tools/test/ProfilePlanTest.java || fail=1
java -cp "$out" com.tj.linkmeter.ProfilePlanTest || fail=1
echo "== PhonePower (the Sleep / Wake button rules)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/PhonePower.java tools/test/PhonePowerTest.java || fail=1
java -cp "$out" com.tj.linkmeter.PhonePowerTest || fail=1
echo "== PowerSequence (the phone's Sleep / Wake steps, against a simulated phone)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/PowerSequence.java tools/test/PowerSequenceTest.java || fail=1
java -cp "$out" com.tj.linkmeter.PowerSequenceTest || fail=1
echo "== Bands (carrier list, lock check, plan) and the band comparison run, against simulated phones"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/RadioState.java app/src/main/java/com/tj/linkmeter/BandCheck.java \
  app/src/main/java/com/tj/linkmeter/SweepPlan.java app/src/main/java/com/tj/linkmeter/BandSweep.java \
  tools/test/BandToolsTest.java tools/test/BandSweepTest.java tools/test/SurveyCommandTest.java || fail=1
java -cp "$out" com.tj.linkmeter.BandToolsTest || fail=1
java -cp "$out" com.tj.linkmeter.BandSweepTest || fail=1
echo "== Scan command (the real shell command, against a 400 KB fake dump, the way Android's shell runs it)"
java -cp "$out" com.tj.linkmeter.SurveyCommandTest || fail=1
echo "== File manager core (paths, kinds, sorting, clipboard, DiskStore) and the transfer engine (copy, move, clashes, cancel, failures, against a model)"
FM=app/src/main/java/com/tj/linkmeter
javac -proc:none -d "$out" $FM/FsEntry.java $FM/FsPath.java $FM/FileKind.java $FM/FileSort.java $FM/FsFormat.java $FM/Clip.java \
  $FM/Store.java $FM/DiskStore.java $FM/TransferEngine.java tools/test/FilesCoreTest.java tools/test/TransferEngineTest.java || fail=1
java -cp "$out" com.tj.linkmeter.FilesCoreTest || fail=1
java -cp "$out" com.tj.linkmeter.TransferEngineTest || fail=1
echo "== SpsFix (the H.264 header fix, against real x264 streams and ffmpeg)"
tools/test-spsfix.sh || fail=1
echo "== AnnexBFramer (cuts the phone's H.264 stream into whole pictures, against real x264 streams and ffprobe)"
tools/test-framer.sh || fail=1
echo "== Paint check (a picture must never be drawn with a translucent Paint: it once dimmed the tablet's picture)"
python3 tools/test/test_paint_alpha.py || fail=1
echo "== Release decision (the pipeline's safety gate, taken from .github/workflows/build.yml)"
python3 tools/test/test_release_decision.py || fail=1
if [ "$fail" -ne 0 ]; then
  echo "OFFLINE TESTS FAILED"
fi
exit "$fail"
