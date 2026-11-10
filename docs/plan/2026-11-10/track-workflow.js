export const meta = {
  name: 'linkmeter-track',
  description: 'One LinkMeter track: implementers in sequence (each saves checkpoints), two independent reviewers, then a fixer that must try to refute each finding',
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
    'Three rules that must never be broken: save ONLY with `NO_RELEASE=1 tools/checkpoint.sh "..."` (never without NO_RELEASE=1); never touch .github/, app/debug.keystore or the applicationId; finish with a clean `git status`, `tools/test.sh` passing and `./gradlew --no-daemon assembleRelease` succeeding on what you pushed.' +
    prior,
    { label: 'impl:' + im.key, phase: 'Implement', schema: IMPL_SCHEMA })
  impl.push({ part: im.key, report: r })
  if (!r) {
    log('implementer ' + im.key + ' returned nothing: the track stops here so the tree can be inspected')
    return { track: A.track, stopped_at: im.key, impl }
  }
  log(im.key + ' done at ' + r.last_commit + (r.build_and_tests_clean ? '' : ' (NOT clean)'))
  if (!r.build_and_tests_clean) {
    return { track: A.track, stopped_at: im.key, reason: 'the implementer says the build or the tests are not clean', impl }
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
    'You are in your own copy of the repository (a git worktree). First run `git log --oneline -1`: it must show commit ' + head + ' (the end of the track). If it shows something else, say so in `checked` and review what `git diff ' + A.base + '..' + head + '` shows, reading files with `git show ' + head + ':<path>`.\n' +
    'BASE = ' + A.base + ', HEAD = ' + head + '.\n' +
    'Scratch folder: ' + A.scratch + '/review-' + A.track + '-' + rv.key + '/\n\n' +
    'YOUR SCOPE: ' + rv.scope + '\n\n' +
    'THE IMPLEMENTERS\' REPORTS (claims to check, not facts):\n' + implText,
    { label: 'review:' + rv.key, phase: 'Review', schema: REVIEW_SCHEMA, isolation: 'worktree' })
    .then(r => ({ reviewer: rv.key, result: r }))
))).filter(Boolean)

const findings = reviews.flatMap(r => ((r.result && r.result.findings) || []).map(f => Object.assign({ by: r.reviewer }, f)))
const reviewFailed = A.reviewers.map(rv => rv.key).filter(k => !reviews.some(r => r.reviewer === k && r.result))
log(findings.length + ' review findings (' + findings.filter(f => f.severity === 'high').length + ' high)' +
  (reviewFailed.length ? '; reviewers that returned nothing: ' + reviewFailed.join(', ') : ''))
if (!findings.length) {
  return { track: A.track, impl, reviews, review_failed: reviewFailed, fix: null }
}

phase('Fix')
const fix = await agent(
  'You are the fixer for track ' + A.track + ' of the LinkMeter Android app.\n' +
  'Read these two files fully first: ' + B + '/COMMON.md and ' + B + '/FIX.md. They are your instructions.\n' +
  'The track\'s commits are ' + A.base + '..' + head + ' (the main tree is at ' + head + ').\n' +
  'Scratch folder: ' + A.scratch + '/fix-' + A.track + '/\n' +
  'Three rules that must never be broken: save ONLY with `NO_RELEASE=1 tools/checkpoint.sh "..."`; never touch .github/, app/debug.keystore or the applicationId; finish with a clean `git status`, `tools/test.sh` passing and `./gradlew --no-daemon assembleRelease` succeeding on what you pushed.\n\n' +
  'THE REVIEWERS\' FINDINGS (claims: try to refute each before you act on it):\n' + cut(JSON.stringify(findings, null, 1), 60000),
  { label: 'fix:' + A.track, phase: 'Fix', schema: FIX_SCHEMA })

return { track: A.track, impl, reviews, review_failed: reviewFailed, fix }
