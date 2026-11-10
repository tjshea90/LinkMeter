#!/usr/bin/env python3
"""Tests the release pipeline's safety gate: the "Decide what to do" step of .github/workflows/build.yml.

The script is taken out of the workflow file itself and run, the way Actions runs it, in throwaway git
repositories that look like the real situation (a main branch with a release tag, and a session branch with
one more commit). Every case says whether main may be fast-forwarded and whether a release is made.
Needs: git and bash only. Run through tools/test.sh.
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WF = os.path.join(ROOT, ".github/workflows/build.yml")

ENV = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t",
           GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")
fails = []
checks = 0


def decide_script():
    """The run: block of the step with id: decide, without the YAML indentation."""
    lines = open(WF).read().split("\n")
    i = next(n for n, l in enumerate(lines) if l.strip() == "id: decide")
    j = next(n for n in range(i, len(lines)) if lines[n].strip() == "run: |")
    out = []
    for l in lines[j + 1:]:
        if l.strip() == "" or l.startswith(" " * 10):
            out.append(l[10:] if l.strip() else "")
        else:
            break
    return "\n".join(out) + "\n"


SCRIPT = decide_script()


def git(cwd, *args):
    r = subprocess.run(["git", *args], cwd=cwd, env=ENV, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("git %s failed: %s %s" % (args, r.stdout, r.stderr))
    return r.stdout.strip()


def write(work, path, text):
    p = os.path.join(work, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)


def make_repo(tmp, tag="v0.2.10"):
    origin = os.path.join(tmp, "origin.git")
    work = os.path.join(tmp, "work")
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", origin], env=ENV, check=True)
    subprocess.run(["git", "clone", "-q", origin, work], env=ENV, check=True, capture_output=True)
    git(work, "checkout", "-q", "-b", "main")
    write(work, "app/Main.java", "class Main {}\n")
    write(work, "app/build.gradle", "android {\n    defaultConfig {\n        applicationId 'com.tj.linkmeter'\n    }\n}\n")
    write(work, "app/debug.keystore", "key-1\n")
    write(work, "gradle.properties", "versionBase=0.2\n")
    write(work, "build.gradle", "// root\n")
    write(work, "settings.gradle", "include ':app'\n")
    write(work, "README.md", "docs\n")
    write(work, ".github/workflows/build.yml", "name: x\n")
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", "initial")
    if tag:
        git(work, "tag", tag)
    git(work, "push", "-q", "origin", "main", "--tags")
    return origin, work


def commit(work, files, msg):
    for path, text in files.items():
        write(work, path, text)
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", msg)
    return git(work, "rev-parse", "HEAD")


def run_decide(work, ref, **env):
    """Runs the step with HEAD checked out as it would be in Actions; returns (outputs, summary)."""
    out = os.path.join(work, ".git", "gh_output")
    summ = os.path.join(work, ".git", "gh_summary")
    for f in (out, summ):
        open(f, "w").close()
    e = dict(ENV, GITHUB_REF_NAME=ref, GITHUB_OUTPUT=out, GITHUB_STEP_SUMMARY=summ, VERSION="0.2.99",
             DRY_RUN="", REHEARSE="", FORCE="")
    e.update(env)
    script = os.path.join(work, ".git", "decide.sh")
    open(script, "w").write(SCRIPT)
    r = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", script], cwd=work, env=e,
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("the decide step failed:\n%s\n%s" % (r.stdout, r.stderr))
    kv = dict(l.split("=", 1) for l in open(out).read().split("\n") if "=" in l)
    return kv, r.stdout


def case(name, want_merge, want_release, build, ref="ccr-test", tag="v0.2.10", **env):
    global checks
    with tempfile.TemporaryDirectory() as tmp:
        origin, work = make_repo(tmp, tag)
        build(work)
        kv, said = run_decide(work, ref, **env)
        got = (kv.get("merge"), kv.get("release"))
        want = (str(want_merge).lower(), str(want_release).lower())
        checks += 1
        ok = got == want
        print("%-4s %-52s merge=%-5s release=%-5s  %s" % ("ok" if ok else "FAIL", name, got[0], got[1],
                                                        said.strip().replace("\n", " | ")[:110]))
        if not ok:
            fails.append("%s: wanted merge/release %s but got %s" % (name, want, got))


def on_branch(files, msg="checkpoint: a change"):
    def build(work):
        git(work, "checkout", "-q", "-b", "ccr-test")
        commit(work, files, msg)
    return build


APP = {"app/Main.java": "class Main { int x; }\n"}

# ---- a session branch, one commit ahead of main
case("app change: merge and release", True, True, on_branch(APP))
case("docs only: merge, no release", True, False, on_branch({"README.md": "more docs\n"}))
case("tools only: merge, no release", True, False, on_branch({"tools/x.sh": "echo\n"}))
case("gradle.properties (version) change: release", True, True, on_branch({"gradle.properties": "versionBase=0.3\n"}))
case("[no-release]: merge but no release", True, False, on_branch(APP, "checkpoint: step [no-release]"))
case("[no-merge]: neither", False, False, on_branch(APP, "checkpoint: step [no-merge]"))
case("[wip]: neither", False, False, on_branch(APP, "checkpoint: step [WIP]"))
case("workflow file changed: by hand", False, False, on_branch({".github/workflows/build.yml": "name: y\n"}))
case("signing key changed: by hand", False, False, on_branch({"app/debug.keystore": "key-2\n"}))
case("application id changed: by hand", False, False, on_branch(
    {"app/build.gradle": "android {\n    defaultConfig {\n        applicationId 'com.other.app'\n    }\n}\n"}))
case("other build.gradle edit is fine", True, True, on_branch(
    {"app/build.gradle": "android {\n    defaultConfig {\n        applicationId 'com.tj.linkmeter'\n        minSdk 29\n    }\n}\n"}))
case("force_release on a docs-only commit", True, True, on_branch({"README.md": "more docs\n"}), FORCE="true")

# ---- main has moved
def diverged(work):
    git(work, "checkout", "-q", "-b", "ccr-test")
    commit(work, APP, "checkpoint: session change")
    git(work, "checkout", "-q", "main")
    commit(work, {"README.md": "someone else\n"}, "a change made on main")
    git(work, "push", "-q", "origin", "main")
    git(work, "checkout", "-q", "--detach", "ccr-test")
case("main has other changes: not a fast-forward", False, False, diverged)


def already_merged(work):
    git(work, "checkout", "-q", "-b", "ccr-test")
    commit(work, APP, "checkpoint: change")
    git(work, "push", "-q", "origin", "ccr-test:main")
case("main already is this commit", False, False, already_merged)


def older_commit(work):
    git(work, "checkout", "-q", "-b", "ccr-test")
    old = commit(work, APP, "checkpoint: older")
    commit(work, {"app/Other.java": "class Other {}\n"}, "checkpoint: newer")
    git(work, "push", "-q", "origin", "HEAD:main")  # the newer run got there first
    git(work, "checkout", "-q", "--detach", old)
case("a newer commit already reached main: skip the older run", False, False, older_commit)

# ---- pushes to main, other branches, tags
case("push to main with an app change: release", False, True, lambda w: commit(w, APP, "an app change"), ref="main")
case("push to main, docs only: no release", False, False, lambda w: commit(w, {"README.md": "d\n"}, "docs"), ref="main")
case("push to main, [no-release]", False, False, lambda w: commit(w, APP, "x [no-release]"), ref="main")
case("another branch: nothing", False, False, on_branch(APP), ref="feature-x")
case("no earlier release tag at all: release", True, True, on_branch({"README.md": "d\n"}), tag=None)


def old_latest_tag_only(work):
    git(work, "checkout", "-q", "-b", "ccr-test")
    commit(work, APP, "checkpoint: change")
case("only the old 'latest' tag exists: release if the app changed since it", True, True, old_latest_tag_only, tag="latest")
case("only the old 'latest' tag exists, docs only: no release", True, False,
     on_branch({"README.md": "d\n"}), tag="latest")

# ---- the manual modes
case("dry run: nothing happens", False, False, on_branch(APP), DRY_RUN="true")
case("rehearsal: draft release, no merge", False, True, on_branch({".github/workflows/build.yml": "name: y\n"}),
     REHEARSE="true")

print("\n%d cases, %d failed" % (checks, len(fails)))
for f in fails:
    print("FAILED:", f)
sys.exit(1 if fails else 0)
