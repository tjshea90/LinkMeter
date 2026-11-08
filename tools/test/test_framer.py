#!/usr/bin/env python3
"""Offline test of AnnexBFramer (the tablet's H.264 picture cutter) against real x264 streams and ffprobe.

The right answer for the real streams comes from ffprobe: its H.264 parser says where every picture of the raw
stream starts and ends. The driver (AnnexBFramerTest.java) feeds each stream to the framer in many ways (as
pictures, merged, in odd pieces, byte by byte, without hints, with hints inside start codes, with false hints in
the middle of pictures, joined in the middle, with junk in front) and compares. It also runs streams built by
hand, where the answer is known exactly.
Needs: ffmpeg + ffprobe with libx264, javac. Run: tools/test-framer.sh
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FRAMER = os.path.join(ROOT, "app/src/main/java/com/tj/linkmeter/AnnexBFramer.java")
DRIVER = os.path.join(HERE, "AnnexBFramerTest.java")
TMP = tempfile.mkdtemp(prefix="framer-")
CLASSES = os.path.join(TMP, "classes")
checks = 0
failed = 0


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def quiet(text):
    return "\n".join(l for l in text.splitlines() if not l.startswith("Picked up JAVA_TOOL_OPTIONS")).strip()


def driver(*args):
    """Runs the Java driver; returns (checks, failed, output)."""
    r = run(["java", "-cp", CLASSES, "com.tj.linkmeter.AnnexBFramerTest", *args])
    out = quiet(r.stdout + "\n" + r.stderr)
    m = re.search(r"(\d+) checks, (\d+) failed", out)
    if not m:
        return 0, 1, "driver did not finish:\n" + out
    return int(m.group(1)), int(m.group(2)), out


def report(name, c, f, out):
    global checks, failed
    checks += c
    failed += f
    print("  %-28s %6d checks, %d failed" % (name, c, f))
    if f:
        print("\n".join("    " + l for l in out.splitlines()[:30]))


# name, lavfi source, extra ffmpeg arguments
SRC_DYN = "testsrc2=size=320x240:rate=30,format=yuv420p"
SRC_BIG = "testsrc2=size=640x480:rate=30,format=yuv420p"
SRC_STATIC = "color=c=0x3060a0:size=320x240:rate=30,format=yuv420p"
STREAMS = [
    ("baseline-1ref", SRC_DYN, 3, "-profile:v baseline -g 30 -bf 0 -x264-params ref=1"),
    ("baseline-4slices", SRC_DYN, 3, "-profile:v baseline -g 30 -bf 0 -x264-params slices=4"),
    ("main-bframes", SRC_DYN, 3, "-profile:v main -g 40 -bf 3"),
    ("high-aud-4refs", SRC_DYN, 3, "-profile:v high -g 25 -bf 2 -x264-params aud=1:ref=4"),
    ("repeat-headers", SRC_DYN, 3, "-profile:v baseline -g 10 -bf 0 -x264-params repeat-headers=1"),
    ("intra-refresh", SRC_DYN, 3, "-profile:v baseline -bf 0 -x264-params intra-refresh=1"),
    ("big-keyframes", SRC_BIG, 2, "-profile:v baseline -g 20 -bf 0 -qp 8"),
    ("static-screen", SRC_STATIC, 3, "-profile:v baseline -g 60 -bf 0"),
    ("many-slices", SRC_DYN, 3, "-profile:v baseline -g 30 -bf 0 -x264-params slice-max-size=500"),
    # a nearly static picture at a constant bitrate: the encoder pads every picture with filler data NAL units
    ("cbr-filler", SRC_STATIC, 3,
     "-profile:v baseline -g 30 -bf 0 -b:v 400k -maxrate 400k -bufsize 400k -x264-params nal-hrd=cbr:force-cfr=1"),
]


def make(name, src, secs, args):
    path = os.path.join(TMP, name + ".h264")
    r = run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", src, "-t", str(secs), "-c:v", "libx264",
             *args.split(), "-f", "h264", path])
    if r.returncode != 0 or not os.path.getsize(path):
        raise RuntimeError("ffmpeg failed for %s:\n%s" % (name, r.stderr))
    p = run(["ffprobe", "-v", "error", "-f", "h264", "-show_entries", "packet=size", "-of", "csv=p=0", path])
    sizes = [int(l.split(",")[0]) for l in p.stdout.splitlines() if l.strip()]
    if not sizes or sum(sizes) != os.path.getsize(path):
        raise RuntimeError("ffprobe's pictures of %s do not tile the stream (%d vs %d)"
                           % (name, sum(sizes), os.path.getsize(path)))
    sizes_file = path + ".sizes"
    with open(sizes_file, "w") as f:
        f.write("\n".join(str(s) for s in sizes) + "\n")
    return path, sizes_file, sizes


def nal_types(path):
    d = open(path, "rb").read()
    types = set()
    i = d.find(b"\x00\x00\x01")
    while i >= 0:
        types.add(d[i + 3] & 31)
        i = d.find(b"\x00\x00\x01", i + 3)
    return types


def main():
    global failed
    os.makedirs(CLASSES)
    r = run(["javac", "-proc:none", "-d", CLASSES, FRAMER, DRIVER])
    if r.returncode != 0:
        print(quiet(r.stdout + r.stderr))
        sys.exit(1)

    print("  (streams built by hand)")
    c, f, out = driver("synthetic", "1", "150")
    report("synthetic x150", c, f, out)

    print("  (real x264 streams, picture boundaries from ffprobe)")
    for name, src, secs, args in STREAMS:
        path, sizes_file, sizes = make(name, src, secs, args)
        c, f, out = driver("real", path, sizes_file, "100")
        report("%s (%d pictures)" % (name, len(sizes)), c, f, out)
        types = nal_types(path)
        # the configurations must really contain what they are meant to exercise
        want = {"high-aud-4refs": 9, "cbr-filler": 12, "repeat-headers": 7}.get(name)
        if want is not None and want not in types:
            print("  FAIL: the stream %s has no NAL unit of type %d (it has %s)" % (name, want, sorted(types)))
            failed += 1
    if failed:
        print("FRAMER TEST FAILED: %d checks, %d failed" % (checks, failed))
        sys.exit(1)
    print("%d checks, 0 failed" % checks)


if __name__ == "__main__":
    main()
