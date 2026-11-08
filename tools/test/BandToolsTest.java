package com.tj.linkmeter;

import java.util.List;

/** Offline test of RadioState, BandCheck and SweepPlan (the Bands tab's logic). Run by tools/test.sh. */
public final class BandToolsTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static final String CA_NSA = "Phone Id=0\n"
            + "  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), mChannelNumber=66611, duplexMode=1, "
            + "mCellBandwidths=[20000, 10000], mOperatorAlphaLong=Verizon, isUsingCarrierAggregation=true, "
            + "mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN nrState=CONNECTED }], mNrFrequencyRange=3}\n"
            + "  mPhysicalChannelConfigs=[{mConnectionStatus=SecondaryServing,mRat=NR,mFrequencyRange=MID,mDownlinkChannelNumber=650016,"
            + "mUplinkChannelNumber=650016,mCellBandwidthDownlinkKhz=100000,mCellBandwidthUplinkKhz=100000,mNetworkType=NR,mPhysicalCellId=77,"
            + "mBand=77,mContextIds=[1]}, {mConnectionStatus=PrimaryServing,mRat=LTE,mFrequencyRange=MID,mDownlinkChannelNumber=66611,"
            + "mUplinkChannelNumber=132147,mCellBandwidthDownlinkKhz=20000,mCellBandwidthUplinkKhz=20000,mNetworkType=LTE,mPhysicalCellId=301,"
            + "mBand=66,mContextIds=[1]}, {mConnectionStatus=SecondaryServing,mRat=LTE,mFrequencyRange=MID,mDownlinkChannelNumber=900,"
            + "mUplinkChannelNumber=18900,mCellBandwidthDownlinkKhz=10000,mCellBandwidthUplinkKhz=10000,mNetworkType=LTE,mPhysicalCellId=301,"
            + "mBand=2,mContextIds=[1]}]\n"
            + "  mTelephonyDisplayInfos=TelephonyDisplayInfo {network=LTE, overrideNetwork=NR_ADVANCED, isRoaming=false}\n";

    public static void main(String[] a) {
        // --- the carrier list of a phone on 5G UW with carrier aggregation
        {
            RadioState s = RadioState.parse(CA_NSA);
            check(s.carriers.size() == 3, "three carriers: " + s.carriers.size());
            check(s.carriers.get(0).primary && s.carriers.get(0).band == 66 && s.carriers.get(0).rat.equals("LTE"),
                    "the primary carrier comes first and is LTE B66");
            check(s.carriers.get(0).channel == 66611 && s.carriers.get(0).bwKhz == 20000, "channel and bandwidth are read");
            boolean n77 = false;
            boolean b2 = false;
            for (RadioState.Carrier c : s.carriers) {
                n77 |= c.nr() && c.band == 77 && c.bwKhz == 100000;
                b2 |= !c.nr() && c.band == 2;
            }
            check(n77 && b2, "the 5G carrier n77 (100 MHz) and the secondary LTE B2 are listed");
            check(s.display.equals("NR_ADVANCED") && RadioState.displayName(s.display).startsWith("5G UW"), "5G UW is recognised");
            check(s.ca && s.bandwidths.size() == 2 && s.bandwidths.get(0) == 20000, "carrier aggregation and its bandwidths");
            check(s.nrState.equals("CONNECTED"), "the 5G state");
            String d = BandCheck.describe(s);
            check(d.startsWith("5G UW") && d.contains("★ LTE B66") && d.contains("5G n77") && d.contains("100 MHz"), "the description: " + d);
        }
        // --- an older style: the status is a number and the band is not printed (it is worked out from the channel)
        {
            RadioState s = RadioState.parse("mPhysicalChannelConfigs=[PhysicalChannelConfig{mCellConnectionStatus=1,mRat=LTE,"
                    + "mFrequencyRange=LOW,mDownlinkChannelNumber=2560,mCellBandwidthDownlinkKhz=10000,mNetworkType=LTE,mPhysicalCellId=12}]\n");
            check(s.carriers.size() == 1 && s.carriers.get(0).primary && s.carriers.get(0).band == 5, "band 5 worked out from channel 2560");
            check(s.display.isEmpty() && !s.ca, "nothing else invented");
        }
        // --- the network type is named only in mNetworkType
        {
            RadioState s = RadioState.parse("mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mNetworkType=LTE_CA,mBand=13,"
                    + "mDownlinkChannelNumber=5230}]");
            check(s.carriers.size() == 1 && s.carriers.get(0).rat.equals("LTE") && s.carriers.get(0).band == 13, "LTE_CA is LTE");
        }
        // --- empty, unreadable, absent, duplicated
        check(RadioState.parse("mPhysicalChannelConfigs=[]").carriers.isEmpty()
                && RadioState.parse("mPhysicalChannelConfigs=[]").rawConfigs.isEmpty(), "an empty list is empty");
        {
            RadioState s = RadioState.parse("mPhysicalChannelConfigs=[something nobody has seen before]");
            check(s.carriers.isEmpty() && s.rawConfigs.contains("something nobody"), "an unreadable list is kept as text");
        }
        check(RadioState.parse("nothing useful here").carriers.isEmpty() && RadioState.parse(null).carriers.isEmpty(), "no lines, no carriers");
        {
            String one = "{mConnectionStatus=PrimaryServing,mRat=LTE,mBand=66,mDownlinkChannelNumber=66611}";
            check(RadioState.parse("mPhysicalChannelConfigs=[" + one + ", " + one + "]").carriers.size() == 1, "the same carrier twice is one");
        }
        {
            RadioState s = RadioState.parse("mServiceState={isUsingCarrierAggregation=true}");
            String d = BandCheck.describe(s);
            check(d.contains("does not list") && d.contains("carrier aggregation"), "no carriers but aggregation: " + d);
        }

        // --- was the lock obeyed?
        List<RadioState.Carrier> ca = RadioState.parse(CA_NSA).carriers; // B66, B2 and n77
        check(BandCheck.verify("", ca).verdict == BandCheck.Verdict.OBEYED, "no lock asked for is always fine");
        check(BandCheck.verify("lte:13", ca).verdict == BandCheck.Verdict.NOT_OBEYED, "asked for B13, on B66+B2: not obeyed");
        check(BandCheck.verify("lte:13", ca).text.contains("B66") && BandCheck.verify("lte:13", ca).text.contains("did not obey"),
                "the text says what it is on: " + BandCheck.verify("lte:13", ca).text);
        check(BandCheck.verify("lte:66,2", ca).verdict == BandCheck.Verdict.OBEYED, "B66 and B2 allowed: obeyed");
        check(BandCheck.verify("lte:66,2", ca).text.contains("5G is still on"), "and it says that 5G is still on");
        check(BandCheck.verify("lte:66,2;nr:77", ca).verdict == BandCheck.Verdict.OBEYED
                && !BandCheck.verify("lte:66,2;nr:77", ca).text.contains("5G is still on"), "lte and nr both allowed");
        check(BandCheck.verify("lte:66,2;nr:41", ca).verdict == BandCheck.Verdict.NOT_OBEYED, "n77 is outside nr:41");
        check(BandCheck.verify("lte:13", null).verdict == BandCheck.Verdict.UNKNOWN, "no carrier list: cannot tell");
        check(BandCheck.verify("lte:13", new java.util.ArrayList<>()).verdict == BandCheck.Verdict.UNKNOWN, "empty carrier list: cannot tell");
        {
            List<RadioState.Carrier> c = RadioState.parse("mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mRat=LTE,mBand=0}]").carriers;
            check(BandCheck.verify("lte:13", c).verdict == BandCheck.Verdict.UNKNOWN, "a carrier with no band: cannot tell");
        }
        {
            List<RadioState.Carrier> only13 = RadioState.parse("mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mRat=LTE,mBand=13}]").carriers;
            check(BandCheck.verify("lte:13", only13).verdict == BandCheck.Verdict.OBEYED, "on B13 as asked");
            check(BandCheck.verify("lte:13;nr:77", only13).verdict == BandCheck.Verdict.OBEYED, "no NR carrier is not a violation of an NR lock");
        }
        check(BandCheck.parse("lte:5,12;nr:71").get("lte").size() == 2 && BandCheck.parse("lte:5,12;nr:71").get("nr").contains(71), "spec parsing");
        check(BandCheck.parse("garbage").isEmpty() && BandCheck.parse(null).isEmpty() && BandCheck.parse("lte:x").get("lte").isEmpty(), "bad specs");

        // --- the best upload
        check(BandCheck.best(new double[] {1.5, 4.2, -1, 3.9}) == 1, "the highest wins");
        check(BandCheck.best(new double[] {-1, -1}) == -1 && BandCheck.best(new double[0]) == -1, "nothing measured: no winner");
        check(BandCheck.best(new double[] {0, -1}) == 0, "a measured zero is still a measurement");
        check(BandCheck.best(new double[] {2, 2}) == 0, "a tie goes to the first");

        // --- the plan
        {
            List<SweepPlan.Step> p = SweepPlan.build(new int[] {13, 5, 66}, new int[] {77}, true);
            check(p.size() == 6, "plan size " + p.size());
            check(p.get(0).spec.isEmpty() && p.get(0).fiveG == null, "first: as it is now");
            check(p.get(1).spec.equals("nr:77") && Boolean.TRUE.equals(p.get(1).fiveG), "then the 5G band with 5G on");
            check(p.get(2).spec.isEmpty() && Boolean.FALSE.equals(p.get(2).fiveG), "then 5G off with no lock");
            check(p.get(3).spec.equals("lte:13") && Boolean.FALSE.equals(p.get(3).fiveG) && p.get(3).label.contains("5G off"), "then 4G bands with 5G off");
            int switches = 0;
            Boolean last = null;
            for (SweepPlan.Step s : p) {
                if (s.fiveG != null) {
                    if (last != null && !last.equals(s.fiveG)) {
                        switches++;
                    }
                    last = s.fiveG;
                }
            }
            check(switches == 1, "5G is switched once, not once per step: " + switches);
        }
        {
            List<SweepPlan.Step> p = SweepPlan.build(new int[] {13, 5}, new int[0], false);
            check(p.size() == 3 && p.get(1).fiveG == null && p.get(1).spec.equals("lte:13"), "without the 5G-off option nothing is switched");
        }
        check(SweepPlan.build(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20}, new int[0], true).size()
                == SweepPlan.MAX_STEPS, "a long list is cut to the maximum");
        {
            int[] b = SweepPlan.parseBands("13, 5 66;66 abc 0 301 9999 x7", 12);
            check(java.util.Arrays.equals(b, new int[] {13, 5, 66, 7}), "band list parsing: " + java.util.Arrays.toString(b));
            check(SweepPlan.parseBands("", 12).length == 0 && SweepPlan.parseBands(null, 12).length == 0, "empty input");
            check(SweepPlan.parseBands("1 2 3 4 5", 3).length == 3, "the limit holds");
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
