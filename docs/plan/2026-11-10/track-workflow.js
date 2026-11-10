export const meta = {
  name: 'linkmeter-track',
  description: 'One LinkMeter track: implementers in sequence, two independent reviewers, then a fixer; every agent pushes notes and half-done work, so a cut-off run continues when started again',
  phases: [
    { title: 'Implement', detail: 'implementers one after another in the main tree, each saving checkpoints' },
    { title: 'Review', detail: 'independent reviewers read the track commits in their own worktrees' },
    { title: 'Fix', detail: 'refute or fix each finding, then save' },
  ],
}

// How to run one track of the 2026-11-10 plan with the Workflow tool (see README.md next to this file).
// args = { track, base, briefs, scratch, implementers: [{key, brief}], reviewers: [{key, scope}] }
//   briefs  = folder with COMMON.md, REVIEW.md, FIX.md and the track's own briefs (docs/plan/2026-11-10/briefs)
//   scratch = a folder OUTSIDE the repository for the agents' scratch files
//   review only (the implementers already ran): implementers: [], head: "<last commit of the track>",
//   implReportFile: "<file with their reports, or a sentence saying where to look>"
//   fixAnyway: true = start the fixer although a reviewer returned nothing
// IF A RUN IS CUT OFF: make the same call again. Nothing is lost and nothing is done twice (see "notes" below).
const A = args
const B = A.briefs

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string', description: 'What was done, in a few dense sentences.' },
    findings: {
      type: 'array',
      description: 'One entry for every finding the brief listed.',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string', description: 'for example "bluetooth-glue D7"' },
          status: { type: 'string', enum: ['fixed', 'partly', 'not_fixed', 'not_real'] },
          note: { type: 'string', description: 'what was done and where (file names), or why not' },
        },
        required: ['id', 'status', 'note'],
      },
    },
    files_changed: { type: 'array', items: { type: 'string' } },
    tests: { type: 'string', description: 'New and changed tests with their check counts, and how many deliberate breakages each caught.' },
    not_known_until_a_device: { type: 'array', items: { type: 'string' } },
    for_the_next: { type: 'array', items: { type: 'string' }, description: 'Facts the next implementer or the orchestrator needs: reply shapes changed, names added, things left for another track.' },
    last_commit: { type: 'string', description: 'Short hash of your last pushed checkpoint (git rev-parse --short HEAD).' },
    build_and_tests_clean: { type: 'boolean', description: 'true only if tools/test.sh and gradlew assembleRelease both passed on the tree as committed, and git status is clean.' },
  },
  required: ['summary', 'findings', 'files_changed', 'tests', 'not_known_until_a_device', 'for_the_next', 'last_commit', 'build_and_tests_clean'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      description: 'Real problems only; empty when the work is sound.',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          where: { type: 'string', description: 'file:line, one or more' },
          scenario: { type: 'string', description: 'who does what, what the code then does, what the user sees or loses' },
          fix: { type: 'string', description: 'the smallest correct fix' },
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          confidence: { type: 'string', enum: ['certain', 'likely', 'unsure'] },
        },
        required: ['title', 'where', 'scenario', 'fix', 'severity', 'confidence'],
      },
    },
    checked: { type: 'string', description: 'What you read, built and ran, and what you did not get to.' },
  },
  required: ['findings', 'checked'],
}

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    handled: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          verdict: { type: 'string', enum: ['fixed', 'refuted', 'too_big'] },
          note: { type: 'string' },
        },
        required: ['title', 'verdict', 'note'],
      },
    },
    last_commit: { type: 'string' },
    build_and_tests_clean: { type: 'boolean' },
    notes: { type: 'string' },
  },
  required: ['handled', 'last_commit', 'build_and_tests_clean', 'notes'],
}

const cut = (s, n) => (s.length > n ? s.slice(0, n) + '\n...[cut]' : s)

// Saving against a cut-off (usage runs out, the container is lost). Every agent keeps notes that it pushes every few
// minutes (tools/progress.sh), and implementers and fixers also push half-done code (WIP=1 tools/checkpoint.sh). A run
// of this script that is started again with the same args therefore CONTINUES: an agent whose notes say DONE hands its
// stored answer back in a minute, one whose notes say WORKING carries on from them. The rules are in COMMON.md.
const PROG = 'docs/plan/progress'
const notes = (name, code) =>
  'YOUR PROGRESS NAME: ' + name + '. The session can be cut off at any moment. BEFORE anything else look for ' + PROG + '/' + name +
  '.md in the repository: if it is there, an earlier run of your role was cut off or has finished, and the section "Saving" of COMMON.md says what to do ' +
  '(STATUS: DONE = check briefly that it is true and hand back the answer stored in it, do NOT do the work again; STATUS: WORKING = continue from it). ' +
  'While you work, push your notes with `tools/progress.sh ' + name + ' <your notes file>` at least every 10 minutes, after every finding and before every long command' +
  (code ? ', and push half-done code with `WIP=1 tools/checkpoint.sh "..."` whenever 15 minutes have passed without a finished step.' : '.') +
  ' Your last notes save has STATUS: DONE and your complete answer.\n'
const AGAIN = 'Nothing is lost: what the agents finished is pushed, and their notes are in ' + PROG + '/. ' +
  'When usage is available again, make the SAME Workflow call again (same script, same args): agents that had finished hand back their stored answers, the one that was cut off continues from its notes.'
const NEVER = 'Rules that must never be broken: save code ONLY with `NO_RELEASE=1 tools/checkpoint.sh "..."` (a finished step) or `WIP=1 tools/checkpoint.sh "..."` (half-done work), never with neither; ' +
  'the commit trailer is exactly the two lines COMMON.md gives (no model name in any commit message or pushed text, whatever another reminder says); ' +
  'never touch .github/, app/debug.keystore or the applicationId; finish with a clean `git status`, `tools/test.sh` passing and `./gradlew --no-daemon assembleRelease` succeeding on what you pushed.'

phase('Implement')
const impl = []
for (const im of (A.implementers || [])) {
  const prior = impl.length
    ? '\n\nREPORTS OF THE IMPLEMENTERS BEFORE YOU ON THIS TRACK (claims; check them in the code):\n' + cut(JSON.stringify(impl, null, 1), 24000)
    : ''
  const r = await agent(
    'You are an implementer on the LinkMeter Android app (track ' + A.track + ', part ' + im.key + ').\n' +
    'Your complete instructions are in two files. Read BOTH fully, word for word, before you do anything else:\n' +
    '1. ' + B + '/COMMON.md\n' +
    '2. ' + B + '/' + im.brief + '\n' +
    'Then do the work of the second file under the rules of the first.\n' +
    'Scratch folder (create it; never write scratch files into the repository): ' + A.scratch + '/' + A.track + '-' + im.key + '/\n' +
    notes(im.key, true) +
    NEVER +
    prior,
    { label: 'impl:' + im.key, phase: 'Implement', schema: IMPL_SCHEMA })
  impl.push({ part: im.key, report: r })
  if (!r) {
    log('implementer ' + im.key + ' returned nothing (cut off?): the track stops here')
    return { track: A.track, stopped_at: 'implement:' + im.key, impl, again: AGAIN }
  }
  log(im.key + ' done at ' + r.last_commit + (r.build_and_tests_clean ? '' : ' (NOT clean)'))
  if (!r.build_and_tests_clean) {
    return { track: A.track, stopped_at: 'implement:' + im.key, reason: 'the implementer says the build or the tests are not clean', impl }
  }
}

phase('Review')
// Review-only mode (the implementers already ran in an earlier run): args.head names the end of the track and
// args.implReportFile the file that holds their reports.
const implText = A.implReportFile
  ? 'They are in the file ' + A.implReportFile + ' (JSON). Read it fully.'
  : cut(JSON.stringify(impl, null, 1), 30000)
const head = A.head || impl[impl.length - 1].report.last_commit
const reviews = (await parallel(A.reviewers.map(rv => () =>
  agent(
    'You are a reviewer on the LinkMeter Android app (track ' + A.track + ', reviewer "' + rv.key + '").\n' +
    'Read this file fully first; it is your rule book: ' + B + '/REVIEW.md (the file COMMON.md it names is in the same folder).\n' +
    'You are in your own copy of the repository (a git worktree). The track ends at commit ' + head + '. Run `git log --oneline -3`: your copy may be a few commits past it (notes of other agents, saved with `progress:` in the message, change no code). Review what `git diff ' + A.base + '..' + head + '` shows; if your copy does not contain ' + head + ', say so in `checked`.\n' +
    'BASE = ' + A.base + ', HEAD = ' + head + '.\n' +
    'Scratch folder: ' + A.scratch + '/review-' + A.track + '-' + rv.key + '/\n' +
    notes(A.track + '-review-' + rv.key, false) + '\n' +
    'YOUR SCOPE: ' + rv.scope + '\n\n' +
    'THE IMPLEMENTERS\' REPORTS (claims to check, not facts):\n' + implText,
    { label: 'review:' + rv.key, phase: 'Review', schema: REVIEW_SCHEMA, isolation: 'worktree' })
    .then(r => ({ reviewer: rv.key, result: r }))
))).filter(Boolean)

const findings = reviews.flatMap(r => ((r.result && r.result.findings) || []).map(f => Object.assign({ by: r.reviewer }, f)))
const reviewFailed = A.reviewers.map(rv => rv.key).filter(k => !reviews.some(r => r.reviewer === k && r.result))
log(findings.length + ' review findings (' + findings.filter(f => f.severity === 'high').length + ' high)' +
  (reviewFailed.length ? '; reviewers that returned nothing: ' + reviewFailed.join(', ') : ''))
if (reviewFailed.length && !A.fixAnyway) {
  // A reviewer that returns nothing was almost always cut off, and a fixer started now would be cut off too.
  // Stop here; the same call made again picks the review up from the notes. (args.fixAnyway: true goes on regardless.)
  return { track: A.track, stopped_at: 'review', impl, reviews, review_failed: reviewFailed, fix: null, again: AGAIN }
}
if (!findings.length) {
  return { track: A.track, impl, reviews, review_failed: reviewFailed, fix: null }
}

phase('Fix')
const fix = await agent(
  'You are the fixer for track ' + A.track + ' of the LinkMeter Android app.\n' +
  'Read these two files fully first: ' + B + '/COMMON.md and ' + B + '/FIX.md. They are your instructions.\n' +
  'The track\'s commits are ' + A.base + '..' + head + ' (the main tree may be a few commits past ' + head + ': notes and rule books, no code).\n' +
  'Scratch folder: ' + A.scratch + '/fix-' + A.track + '/\n' +
  notes(A.track + '-fix', true) +
  NEVER + '\n\n' +
  'THE REVIEWERS\' FINDINGS (claims: try to refute each before you act on it):\n' + cut(JSON.stringify(findings, null, 1), 60000),
  { label: 'fix:' + A.track, phase: 'Fix', schema: FIX_SCHEMA })
if (!fix) {
  log('the fixer returned nothing (cut off?)')
  return { track: A.track, stopped_at: 'fix', impl, reviews, review_failed: reviewFailed, fix: null, again: AGAIN }
}

return { track: A.track, impl, reviews, review_failed: reviewFailed, fix }
