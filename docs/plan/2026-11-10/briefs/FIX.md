# LinkMeter: fixing what the reviewers found

You are the FIXER for one track. Read `briefs/COMMON.md` next to this file first: all of its rules and its way of saving
apply to you (you are an implementer).

Two reviewers read the track's commits and reported the findings appended to your task. A finding is a claim, and reviewers
are sometimes wrong. For EACH finding:

1. Try to REFUTE it: read the code it names, in full, and follow the scenario step by step. If the scenario cannot happen, or
   the code already handles it, the finding is refuted: do not change anything for it, and say in one or two sentences why.
2. If it stands, fix it at the root, the way a careful maintainer would. If the reviewer's suggested fix is wrong or
   incomplete, do the right thing instead. Add or extend an offline test when the rule is testable, and show the test notices
   the rule being broken.
3. If it stands but fixing it properly is bigger than this track (a new protocol, a redesign), do not half-fix it: say so and
   describe what is needed.

Two reviewers may have reported the same thing in different words: handle it once.

Then run `tools/test.sh` and `./gradlew --no-daemon assembleRelease`, and `tools/preview.sh` if a screen changed (look at the
pictures), and save as COMMON says (with `NO_RELEASE=1`). In HANDOFF's track list add half a sentence to the track's line
only if something a new session must know has changed.
