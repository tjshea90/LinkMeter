# LinkMeter

Read `HANDOFF.md` first: it says what is done, what is not, and how to resume.

Rules for this project:
- Save often: run `tools/checkpoint.sh "what changed"` after every meaningful step.
  Assume the session can be cut off at any moment.
- Keep `HANDOFF.md` Status current before each checkpoint.
- No third-party libraries. The tablet is a low-RAM Go device.
- Cloud sessions work only on their designated `ccr-*` branch and must not push to `main` without the user's say-so.
  CI compile-checks that branch but only `main` publishes the `latest` APK. Before starting, run `git fetch --all` and
  `git log --oneline main..origin/<newest ccr-* branch>` so unmerged work from an earlier session is not missed.
- Before calling a feature done, trace it end to end (request -> `Api.handle` route -> handler -> reply -> parse). CI only proves it compiles.
