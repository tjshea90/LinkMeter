package com.tj.linkmeter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the real shell command that Cells.survey sends to the phone (RadioState.SURVEY_COMMAND) against a fake `dumpsys`
 * that prints a 400 KB registry dump, the way Android's shell would run it: with NO built-in printf.
 *
 * Why: the first version of the Bands scan held the dump in a shell variable and handed it to printf. On a computer that
 * works, because bash has a built-in printf. On the phone printf is a separate program, one argument may be at most 128 KB,
 * and the dump is bigger, so the shell said "Argument list too long" and the scan came back empty: no cells, no carriers,
 * and every band lock "could not be checked". Offline compilation and every other test passed. This test turns the built-in
 * printf off (enable -n printf), so Linux enforces the same limit, and also shows that the old command fails that way, so
 * the test is known to be able to fail. Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class SurveyCommandTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    /** Runs a command in bash with the built-in printf off and a fake dumpsys first on the PATH. */
    static String run(Path bin, String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "enable -n printf; " + command);
        pb.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("the command did not end");
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    static String fakeDump() {
        StringBuilder sb = new StringBuilder();
        pad(sb, 2000, "before");
        // slot 0: no SIM in use
        sb.append("Phone Id=0\n")
                .append("  mCallState=0\n")
                .append("  mServiceState={mVoiceRegState=1(OUT_OF_SERVICE), mDataRegState=1(OUT_OF_SERVICE), mChannelNumber=-1, ")
                .append("isUsingCarrierAggregation=false, mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS nrState=NONE }]}\n")
                .append("  mCellInfo=[]\n")
                .append("  mPhysicalChannelConfigs=[]\n")
                .append("  mTelephonyDisplayInfos=TelephonyDisplayInfo {network=UNKNOWN, overrideNetwork=NONE, isRoaming=false}\n");
        pad(sb, 1000, "between");
        // slot 1: on 5G UW with carrier aggregation
        sb.append("Phone Id=1\n")
                .append("  mCallState=0\n")
                .append("  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=66611, duplexMode=1, ")
                .append("mCellBandwidths=[20000, 10000], isUsingCarrierAggregation=true, ")
                .append("mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=CS nrState=NONE }, ")
                .append("NetworkRegistrationInfo{ domain=PS transportType=WWAN nrState=CONNECTED }]}\n")
                .append("  mCellInfo=[CellInfoLte:{mRegistered=YES mCellConnectionStatus=1 CellIdentityLte:{mCi=*** mPci=[****] ")
                .append("mEarfcn=[****] mBands=[66] mBandwidth=20000 mMcc=311 mMnc=480} CellSignalStrengthLte: rssi=-80 rsrp=-104 ")
                .append("rsrq=-12 rssnr=4 cqi=7 ta=2147483647 level=2}]\n")
                .append("  mPhysicalChannelConfigs=[{mConnectionStatus=SecondaryServing,mRat=NR,mFrequencyRange=MID,")
                .append("mDownlinkChannelNumber=650016,mCellBandwidthDownlinkKhz=100000,mBand=77}, ")
                .append("{mConnectionStatus=PrimaryServing,mRat=LTE,mDownlinkChannelNumber=66611,mCellBandwidthDownlinkKhz=20000,mBand=66}, ")
                .append("{mConnectionStatus=SecondaryServing,mRat=LTE,mDownlinkChannelNumber=900,mCellBandwidthDownlinkKhz=10000,mBand=2}]\n")
                .append("  mTelephonyDisplayInfos=TelephonyDisplayInfo {network=LTE, overrideNetwork=NR_ADVANCED, isRoaming=false}\n");
        pad(sb, 1000, "after");
        return sb.toString();
    }

    static void pad(StringBuilder sb, int lines, String tag) {
        String filler = "x".repeat(90);
        for (int i = 0; i < lines; i++) {
            sb.append("  mOther_").append(tag).append('_').append(i).append('=').append(filler).append('\n');
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("survey-test");
        Path dump = dir.resolve("dump.txt");
        Files.write(dump, fakeDump().getBytes(StandardCharsets.UTF_8));
        Path dumpsys = dir.resolve("dumpsys");
        Files.write(dumpsys, ("#!/bin/sh\ncat '" + dump + "'\n").getBytes(StandardCharsets.UTF_8));
        check(dumpsys.toFile().setExecutable(true), "the fake dumpsys can be run");
        check(Files.size(dump) > 300_000, "the fake dump is far over the 128 KB one argument can hold: " + Files.size(dump));

        // 1. the command the phone really gets
        String out = run(dir, RadioState.SURVEY_COMMAND);
        check(!out.contains("Argument list too long") && !out.contains("not found"), "no shell error: " + first(out));
        int marker = out.indexOf(RadioState.EXTRA_MARKER);
        check(marker > 0, "the two parts are told apart by the marker");
        String cells = marker > 0 ? out.substring(0, marker) : out;
        check(cells.contains("CellInfoLte") && cells.contains("mChannelNumber=66611"), "the cell part holds the cell and the serving channel");
        check(cells.lines().count() <= 8, "the cell part is cut at 8 lines: " + cells.lines().count());
        RadioState rs = RadioState.parse(out);
        check(rs.carriers.size() == 3 && rs.carriers.get(0).band == 66 && rs.carriers.get(0).primary,
                "the carriers are read from the output: " + rs.carriers.size());
        check(rs.display.equals("NR_ADVANCED") && rs.ca && rs.bandwidths.size() == 2 && rs.nrState.equals("CONNECTED"),
                "the label and the aggregation facts of the slot in use: " + rs.display + ", " + rs.ca + ", " + rs.bandwidths + ", " + rs.nrState);

        // 2. the first version of the command fails the way it failed on the phone: the test can fail
        String broken = "D=$(dumpsys telephony.registry 2>&1); printf '%s\\n' \"$D\" | grep -iE 'CellInfo|mServiceState' | cut -c1-9000 | head -8";
        String bad = run(dir, broken);
        check(bad.contains("Argument list too long"), "the old command fails here as it did on the phone: " + first(bad));

        // 3. nothing in the app holds a dump in a shell variable again
        Path src = Paths.get("app/src/main/java");
        if (Files.isDirectory(src)) {
            try (Stream<Path> files = Files.walk(src)) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    check(!text.contains("$(dumpsys"), f + " holds the output of dumpsys in a shell variable: a big dump cannot be passed on (Argument list too long)");
                }
            }
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String first(String s) {
        String t = s.trim();
        int nl = t.indexOf('\n');
        t = nl > 0 ? t.substring(0, nl) : t;
        return t.length() > 160 ? t.substring(0, 160) : t;
    }
}
