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

        // --- a phone that can hold two SIMs prints every field once per slot; the slot that is in use lists the carriers
        {
            String[] u = CA_NSA.split("\n");
            String svcUsed = u[1];
            String cfgUsed = u[2];
            String dispUsed = u[3];
            String svcIdle = "  mServiceState={mVoiceRegState=1(OUT_OF_SERVICE), isUsingCarrierAggregation=false, "
                    + "mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN nrState=NONE }]}";
            String cfgIdle = "  mPhysicalChannelConfigs=[]";
            String dispIdle = "  mTelephonyDisplayInfos=TelephonyDisplayInfo {network=UNKNOWN, overrideNetwork=NONE, isRoaming=false}";
            // whole dump, slot 0 idle then slot 1 in use, and the other way round
            RadioState slot1 = RadioState.parse("Phone Id=0\n" + svcIdle + "\n" + cfgIdle + "\n" + dispIdle + "\nPhone Id=1\n"
                    + svcUsed + "\n" + cfgUsed + "\n" + dispUsed + "\n");
            check(slot1.carriers.size() == 3 && slot1.carriers.get(0).band == 66, "two slots, the second in use: its carriers: " + slot1.carriers.size());
            check(slot1.display.equals("NR_ADVANCED") && slot1.ca && slot1.bandwidths.size() == 2 && slot1.nrState.equals("CONNECTED"),
                    "two slots, the second in use: its label and its aggregation facts (" + slot1.display + ", " + slot1.ca + ", " + slot1.bandwidths
                    + ", " + slot1.nrState + ")");
            RadioState b = RadioState.parse("Phone Id=0\n" + svcUsed + "\n" + cfgUsed + "\n" + dispUsed + "\nPhone Id=1\n"
                    + svcIdle + "\n" + cfgIdle + "\n" + dispIdle + "\n");
            check(b.carriers.size() == 3 && b.display.equals("NR_ADVANCED") && b.ca && b.nrState.equals("CONNECTED"),
                    "two slots, the first in use");
            // what Cells.survey hands over: only the matching lines (no "Phone Id" lines), the two parts told apart by a marker
            RadioState c = RadioState.parse(svcIdle + "\n" + svcUsed + "\n##EXTRA\n" + cfgIdle + "\n" + cfgUsed + "\n" + dispIdle + "\n"
                    + dispUsed + "\n");
            check(c.carriers.size() == 3 && c.display.equals("NR_ADVANCED") && c.ca && c.bandwidths.size() == 2,
                    "as the survey prints it (slot 1 in use): " + c.carriers.size() + " carriers, " + c.display + ", ca " + c.ca);
            // the lines do not match up (a slot's label is missing): the carriers still come from the slot that has them
            RadioState d = RadioState.parse(svcIdle + "\n" + svcUsed + "\n##EXTRA\n" + cfgIdle + "\n" + cfgUsed + "\n" + dispUsed + "\n");
            check(d.carriers.size() == 3 && d.display.equals("NR_ADVANCED"), "lines that do not match up: carriers kept, the label found");
            // both slots list carriers: the one with more is the data one
            String cfgOne = "  mPhysicalChannelConfigs=[{mConnectionStatus=PrimaryServing,mRat=LTE,mBand=13,mDownlinkChannelNumber=5230}]";
            RadioState e = RadioState.parse(svcIdle + "\n" + svcUsed + "\n##EXTRA\n" + cfgOne + "\n" + cfgUsed + "\n" + dispIdle + "\n"
                    + dispUsed + "\n");
            check(e.carriers.size() == 3 && e.carriers.get(0).band == 66 && e.display.equals("NR_ADVANCED"), "both slots list carriers: the larger list");
            // a single slot is read exactly as before
            RadioState f = RadioState.parse(svcUsed + "\n##EXTRA\n" + cfgUsed + "\n" + dispUsed + "\n");
            check(f.carriers.size() == 3 && f.display.equals("NR_ADVANCED") && f.ca, "one slot");
            // nothing readable in either slot: the text that is there is kept so it can be looked at
            RadioState g = RadioState.parse(svcIdle + "\n" + svcUsed + "\n##EXTRA\n" + cfgIdle + "\n  mPhysicalChannelConfigs=[odd]\n");
            check(g.carriers.isEmpty() && g.rawConfigs.equals("[odd]"), "unreadable text of the second slot is kept: " + g.rawConfigs);
        }
        // --- the 5G state is printed for every registration (voice first): the one that says something wins
        check(RadioState.parse("mServiceState={domain=CS nrState=NONE domain=PS nrState=CONNECTED}").nrState.equals("CONNECTED"),
                "5G state: the data registration, not the voice one");
        check(RadioState.parse("mServiceState={domain=CS nrState=NONE domain=PS nrState=NONE}").nrState.equals("NONE"), "5G state: none");
        check(RadioState.parse("mServiceState={domain=PS nrState=RESTRICTED domain=CS nrState=NONE}").nrState.equals("RESTRICTED"),
                "5G state: the first that says something");

        // --- is a listed 5G carrier really in use? The list can be out of date for a while after 5G was switched off
        {
            RadioState x = new RadioState();
            x.carriers.add(new RadioState.Carrier("LTE", 66, 66611, 15000, true));
            check(!x.listsNr() && !x.nrCarrierInUse(false) && !x.staleNr(false), "no 5G carrier listed: none in use, nothing stale");
            x.carriers.add(new RadioState.Carrier("NR", 77, 650000, 60000, false));
            x.carriers.add(new RadioState.Carrier("NR", 2, 394000, 10000, false));
            check(x.listsNr() && x.nrCarrierInUse(false) && !x.staleNr(false), "listed and the phone says nothing else: the list is believed");
            x.display = "NR_ADVANCED";
            x.nrState = "CONNECTED";
            check(x.nrCarrierInUse(false) && x.nrCarrierInUse(true) && !x.staleNr(true), "5G UW label and CONNECTED: in use, whatever else is known");
            x.display = "NONE";
            x.nrState = "NONE";
            check(!x.nrCarrierInUse(false) && x.staleNr(false), "listed, but label NONE and state NONE: an out-of-date list");
            x.display = "NONE";
            x.nrState = "";
            check(x.nrCarrierInUse(false), "label NONE and no state: not enough to overrule the list...");
            check(!x.nrCarrierInUse(true) && x.staleNr(true), "...unless 5G is known to be switched off: then the label settles it");
            x.display = "";
            x.nrState = "NONE";
            check(x.nrCarrierInUse(false) && x.nrCarrierInUse(true), "a state but no label: the list stands");
            x.display = "NONE";
            x.nrState = "CONNECTED";
            check(x.nrCarrierInUse(false) && x.nrCarrierInUse(true), "state CONNECTED is never overruled by a missing 5G label");
            x.display = "NR_NSA";
            x.nrState = "NOT_RESTRICTED";
            check(x.nrCarrierInUse(false) && !x.staleNr(false), "the plain 5G label counts");
            x.display = "LTE_CA";
            x.nrState = "RESTRICTED";
            check(x.staleNr(false) && !x.nrCarrierInUse(false), "LTE_CA label and RESTRICTED: an out-of-date list");
            String ev = x.fiveGEvidence();
            check(ev.contains("B66 + n77 + n2") && ev.contains("LTE_CA") && ev.contains("RESTRICTED"), "the evidence text names list, label and state: " + ev);
            check(new RadioState().fiveGEvidence().contains("empty") && new RadioState().fiveGEvidence().contains("not given"),
                    "and says what the phone did not give: " + new RadioState().fiveGEvidence());

            // the words of the table and the Now-using box
            x.display = "NONE";
            x.nrState = "NONE";
            String t = BandSweep.usingText(x);
            check(t.startsWith("B66 [") && t.contains("n77 + n2") && t.contains("old entries"), "an out-of-date list is not shown as 5G: " + t);
            check(BandCheck.describe(x).contains("probably an out-of-date list"), "the Now-using text says so: " + BandCheck.describe(x));
            x.display = "NR_ADVANCED";
            x.nrState = "CONNECTED";
            check(BandSweep.usingText(x).equals("B66 + n77 + n2 (5G UW)"), "a real 5G connection is shown as it is: " + BandSweep.usingText(x));
            check(!BandCheck.describe(x).contains("out-of-date"), "and gets no such remark");
            x.display = "NONE";
            x.nrState = "";
            check(BandSweep.usingText(x).equals("B66 + n77 + n2"), "label only, 5G not known to be off: the list stands: " + BandSweep.usingText(x));
            check(BandSweep.usingText(x, true).startsWith("B66 ["), "label only, 5G known to be off: old entries named: " + BandSweep.usingText(x, true));
            RadioState onlyNr = new RadioState();
            onlyNr.carriers.add(new RadioState.Carrier("NR", 77, 650000, 60000, true));
            onlyNr.display = "NONE";
            onlyNr.nrState = "NONE";
            check(BandSweep.usingText(onlyNr, true).equals("n77"), "with no 4G carrier to fall back on nothing is hidden: " + BandSweep.usingText(onlyNr, true));
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
