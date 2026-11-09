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
echo "== 5G leg (NrLeg: the words the phone and the tablet share about it; never claims a state the phone did not report)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/NrLeg.java tools/test/NrLegTest.java || fail=1
java -cp "$out" com.tj.linkmeter.NrLegTest || fail=1
echo "== Fair comparison of settings (LeverRun: random order within rounds, a sign-test verdict, a setting counts only when the phone really took it, always put back; simulated phones)"
javac -proc:none -cp "$out" -d "$out" app/src/main/java/com/tj/linkmeter/LeverRun.java tools/test/LeverRunTest.java || fail=1
java -cp "$out" com.tj.linkmeter.LeverRunTest || fail=1
echo "== Safety net (GuardPolicy: when the phone undoes LinkMeter's changes by itself: never fights the user, always acts when data stays down)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/GuardPolicy.java tools/test/GuardPolicyTest.java || fail=1
java -cp "$out" com.tj.linkmeter.GuardPolicyTest || fail=1
echo "== Safety check (SafetyReport: never says 'nothing is changed' while something is; every combination)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/SafetyReport.java tools/test/SafetyReportTest.java || fail=1
java -cp "$out" com.tj.linkmeter.SafetyReportTest || fail=1
echo "== Best spot for the phone (SignalHunt: the 10 s average, the upload score, the beep, marked spots)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/SignalHunt.java tools/test/SignalHuntTest.java || fail=1
java -cp "$out" com.tj.linkmeter.SignalHuntTest || fail=1
echo "== Service-menu finder (MenuScan: reading the phone's text, refusing anything unsafe, and the real shell commands with no built-in printf)"
javac -proc:none -d "$out" app/src/main/java/com/tj/linkmeter/MenuScan.java tools/test/MenuScanTest.java || fail=1
java -cp "$out" com.tj.linkmeter.MenuScanTest || fail=1
echo "== File manager core (paths, kinds, sorting, clipboard, DiskStore) and the transfer engine (copy, move, clashes, cancel, failures, against a model)"
FM=app/src/main/java/com/tj/linkmeter
javac -proc:none -d "$out" $FM/FsEntry.java $FM/FsPath.java $FM/FileKind.java $FM/FileSort.java $FM/FsFormat.java $FM/Clip.java \
  $FM/Store.java $FM/DiskStore.java $FM/TransferEngine.java tools/test/FilesCoreTest.java tools/test/TransferEngineTest.java || fail=1
java -cp "$out" com.tj.linkmeter.FilesCoreTest || fail=1
java -cp "$out" com.tj.linkmeter.TransferEngineTest || fail=1
echo "== File protocol (the real server code and the real client over a real socket: every route, an old app, the engine across the wire)"
javac -proc:none -cp "$out" -d "$out" $FM/Json.java $FM/Http.java $FM/Client.java $FM/FsJobs.java $FM/FsSearch.java $FM/FsApi.java $FM/FsHttp.java $FM/RemoteStore.java \
  $FM/PeerLink.java tools/test/JsonTest.java tools/test/FsProtocolTest.java || fail=1
java -cp "$out" com.tj.linkmeter.JsonTest || fail=1
java -cp "$out" com.tj.linkmeter.FsProtocolTest || fail=1
echo "== Texts, pure parts (phone-number keys, merging the phone's messages with the tablet's log, the MMS encoder read back by an independent decoder)"
javac -proc:none -encoding UTF-8 -cp "$out" -d "$out" $FM/PhoneNumbers.java $FM/Sms.java $FM/SmsMerge.java $FM/MmsPdu.java $FM/MmsFit.java $FM/TextTime.java tools/test/SmsPureTest.java || fail=1
java -cp "$out" com.tj.linkmeter.SmsPureTest || fail=1
echo "== Texts protocol (the real server code and the real client over a real socket against a fake phone: privacy, permissions, lists, threads, sending, files, the outbox log)"
javac -proc:none -encoding UTF-8 -cp "$out" -d "$out" $FM/SmsJson.java $FM/SmsOutbox.java $FM/SmsStaging.java $FM/SmsApi.java $FM/SmsHttp.java $FM/RemoteSms.java \
  tools/test/SmsProtocolTest.java || fail=1
java -cp "$out" com.tj.linkmeter.SmsProtocolTest || fail=1
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
