#!/usr/bin/env python3
"""Guards against the bug that made the tablet's picture look dimmed.

A Paint's alpha also applies when it draws a bitmap. PhoneScreenView once drew the phone's picture with the same
Paint it used for the touch marker (colour 0x80FFFFFF, 50% alpha), so after the first touch every picture was drawn
at half brightness: the screen seemed to dim whenever the sharp still picture took over from the video.
This check fails if a Paint that draws a bitmap is given a colour or an alpha anywhere in the same file.
Run: python3 tools/test/test_paint_alpha.py   (tools/test.sh does)
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def split_args(text):
    """Splits a call's argument text at top-level commas."""
    out, depth, cur = [], 0, ""
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    out.append(cur.strip())
    return out


def bitmap_paints(src):
    """Names of the Paint variables passed as the last argument of drawBitmap calls."""
    names = set()
    for m in re.finditer(r"\bdrawBitmap\(", src):
        i, depth = m.end(), 1
        while i < len(src) and depth:
            depth += {"(": 1, ")": -1}.get(src[i], 0)
            i += 1
        args = split_args(src[m.end():i - 1])
        if args and re.fullmatch(r"[A-Za-z_]\w*", args[-1]) and args[-1] != "null":
            names.add(args[-1])
    return names


def check(src):
    """Returns a list of problems in this source text."""
    problems = []
    for name in sorted(bitmap_paints(src)):
        for m in re.finditer(r"\b" + re.escape(name) + r"\.(setColor|setAlpha|setARGB)\(", src):
            line = src.count("\n", 0, m.start()) + 1
            problems.append("line %d: %s.%s(...) but %s also draws a bitmap" % (line, name, m.group(1), name))
    return problems


def self_test():
    bad = "Paint p = new Paint(); void d(Canvas c){ c.drawBitmap(b, null, r, p); p.setColor(0x80FFFFFF); }"
    good = "Paint pic = new Paint(); Paint p = new Paint(); void d(Canvas c){ c.drawBitmap(b, null, r, pic); p.setColor(1); }"
    nested = "void d(Canvas c){ c.drawBitmap(Bitmap.create(a, b), Math.max(1, 2), y, q); q.setAlpha(3); }"
    assert check(bad) and not check(good) and check(nested), "the checker itself is broken"


def main():
    self_test()
    checked, failed = 0, 0
    for path in sorted(glob.glob(os.path.join(ROOT, "app/src/main/java/**/*.java"), recursive=True)):
        src = open(path, encoding="utf-8").read()
        names = bitmap_paints(src)
        if not names:
            continue
        checked += 1
        for p in check(src):
            failed += 1
            print("  FAIL: %s: %s" % (os.path.relpath(path, ROOT), p))
    print("%d files that draw bitmaps checked, %d problems" % (checked, failed))
    sys.exit(1 if failed else 0)


main()
