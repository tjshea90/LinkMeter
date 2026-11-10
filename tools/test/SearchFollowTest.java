package com.tj.linkmeter;

/**
 * Offline test of SearchFollow (what became of the radio restart). Run by tools/test.sh. Prints "N checks, M failed".
 * What matters: "done" is only said when the phone said it, a silent phone is the restart and not a failure, a failed restart says
 * why, and a restart that never ends is given up with words that do not claim success.
 */
public final class SearchFollowTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    public static void main(String[] a) {
        {
            SearchFollow f = new SearchFollow();
            check(f.look(true, "running") == null, "running: keep asking");
            check(f.look(false, null) == null && f.look(false, "") == null, "no answer is the restart, not a failure");
            SearchFollow.Outcome o = f.look(true, "done");
            check(o != null && o.ok && o.text.contains("searched for a network again"), "done");
        }
        {
            SearchFollow f = new SearchFollow();
            SearchFollow.Outcome o = f.look(true, "failed: the radio could not be switched back on (boom). Turn airplane mode on and off.");
            check(o != null && !o.ok && o.text.contains("could not restart its radio") && o.text.contains("boom"), "failed says why: " + (o == null ? null : o.text));
            o = new SearchFollow().look(true, "failed");
            check(o != null && !o.ok && o.text.equals("The phone could not restart its radio."), "failed with no reason");
        }
        {
            SearchFollow f = new SearchFollow();
            SearchFollow.Outcome o = f.look(true, "unknown action: radio_cycle_state");
            check(o != null && o.ok, "a phone app from before the question restarted its radio while answering: it is over");
        }
        {
            // "none": no record of a restart
            SearchFollow f = new SearchFollow();
            check(f.look(true, "none") == null && f.look(true, "none") == null, "none once or twice: keep asking");
            SearchFollow.Outcome o = f.look(true, "none");
            check(o != null && !o.ok && o.text.contains("no record"), "none three times in a row: no record of it");
            f = new SearchFollow();
            f.look(true, "none");
            f.look(true, "none");
            check(f.look(true, "running") == null, "running in between starts the count again");
            check(f.look(true, "none") == null && f.look(true, "none") == null, "so two more nones are still not enough");
        }
        {
            SearchFollow f = new SearchFollow();
            SearchFollow.Outcome o = f.giveUp();
            check(!o.ok && o.text.contains("did not say that the restart ended") && !o.text.toLowerCase().contains("searched"), "giving up claims nothing");
        }
        check(SearchFollow.EVERY_MS == 3_000L && SearchFollow.GIVE_UP_MS == 120_000L, "the waits");
        // a whole story: running, silence while the hotspot is down, running, done
        {
            SearchFollow f = new SearchFollow();
            Object[][] script = {{true, "running"}, {false, null}, {false, null}, {false, null}, {true, "running"}, {true, "done"}};
            SearchFollow.Outcome last = null;
            int looks = 0;
            for (Object[] s : script) {
                looks++;
                last = f.look((Boolean) s[0], (String) s[1]);
                if (last != null) {
                    break;
                }
            }
            check(last != null && last.ok && looks == script.length, "the story ends with the phone's own word, at the last look: " + looks);
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
