#!/usr/bin/env python3
"""Offline test of SpsFix (the H.264 header rewrite) against real x264 streams and ffmpeg's own parser.

For every stream configuration it makes three variants of the stream (as x264 wrote it, with the VUI
removed, with the VUI kept but without bitstream restrictions), runs SpsFix on each, then asks ffmpeg:
  * does ffmpeg's header trace show the fields we intended (no reordering, small picture buffer), with every
    other field of the SPS unchanged and the bit positions our parser found matching ffmpeg's?
  * does the rewritten stream decode to exactly the same pictures (for streams without B-frames)?
Needs: ffmpeg with libx264 and the trace_headers filter, javac. Run: tools/test-spsfix.sh
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SPSFIX = os.path.join(ROOT, "app/src/main/java/com/tj/linkmeter/SpsFix.java")
DRIVER = os.path.join(HERE, "SpsFixTest.java")
TMP = tempfile.mkdtemp(prefix="spsfix-")
CLASSES = os.path.join(TMP, "classes")
fails = []
checks = 0


def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        fails.append(msg)
        print("  FAIL:", msg)


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True)


def quiet(text):
    return "\n".join(l for l in text.splitlines() if not l.startswith("Picked up JAVA_TOOL_OPTIONS")).strip()


def java(*args):
    r = run(["java", "-cp", CLASSES, "com.tj.linkmeter.SpsFixTest", *args])
    if r.returncode != 0:
        raise RuntimeError("java %s failed:\n%s\n%s" % (args, r.stdout, quiet(r.stderr)))
    return r.stdout.strip()


LINE = re.compile(r"^\[trace_headers[^\]]*\]\s+(\d+)\s+(\S+)\s+([01]*)\s*=\s*(-?\d+)\s*$")


def sps_trace(path):
    """ffmpeg's own parse of the first SPS: a list of (bit position, name, value)."""
    r = run(["ffmpeg", "-v", "info", "-i", path, "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-"])
    out, inside = [], False
    for line in r.stderr.splitlines():
        if not line.startswith("[trace_headers"):
            continue
        body = re.sub(r"^\[trace_headers[^\]]*\]\s*", "", line)
        if body == "Sequence Parameter Set":
            if inside:
                break
            inside = True
            continue
        m = LINE.match(line)
        if m is None:
            if inside:
                break  # the next header section
            continue
        if inside:
            out.append((int(m.group(1)), m.group(2), int(m.group(4))))
    return out


def idx(tr, name):
    for i, (_, n, _) in enumerate(tr):
        if n == name:
            return i
    return -1


def val(tr, name, default=None):
    i = idx(tr, name)
    return tr[i][2] if i >= 0 else default


def pos(tr, name):
    i = idx(tr, name)
    return tr[i][0] if i >= 0 else None


def core(tr):
    i = idx(tr, "vui_parameters_present_flag")
    return [(n, v) for (_, n, v) in tr[:i]]


def vui_head(tr):
    """VUI fields before bitstream_restriction_flag."""
    i, j = idx(tr, "vui_parameters_present_flag"), idx(tr, "bitstream_restriction_flag")
    return [(n, v) for (_, n, v) in tr[i + 1:j]] if j >= 0 else []


def framemd5(path):
    r = run(["ffmpeg", "-v", "error", "-i", path, "-f", "framemd5", "-"])
    return [l for l in r.stdout.splitlines() if l and not l.startswith("#")], r.stderr.strip()


def nal_types(data):
    """(offset of start code, nal type) for each NAL unit."""
    out, i = [], 0
    while True:
        i = data.find(b"\x00\x00\x01", i)
        if i < 0 or i + 3 >= len(data):
            return out
        out.append((i, data[i + 3] & 0x1F))
        i += 3


CASES = [
    # name, size, ffmpeg arguments, has B-frames, (syntax element, expected value) the stream must contain
    ("baseline", "320x576", ["-profile:v", "baseline"], False, ("profile_idc", 66)),
    ("main_nob", "320x576", ["-profile:v", "main", "-bf", "0"], False, None),
    ("main_bframes", "320x576", ["-profile:v", "main", "-bf", "2"], True, ("profile_idc", 77)),
    ("high", "320x576", ["-profile:v", "high", "-bf", "0", "-coder", "1", "-x264-params", "8x8dct=1"], False,
     ("profile_idc", 100)),

    ("high422", "320x576", ["-profile:v", "high422", "-pix_fmt", "yuv422p", "-bf", "0"], False,
     ("chroma_format_idc", 2)),
    ("high444", "320x576", ["-profile:v", "high444", "-pix_fmt", "yuv444p", "-bf", "0"], False,
     ("chroma_format_idc", 3)),
    ("hrd_cbr", "320x576", ["-profile:v", "high", "-bf", "0", "-b:v", "1M", "-maxrate", "1M", "-bufsize", "1M",
                            "-x264-params", "nal-hrd=cbr"], False, ("nal_hrd_parameters_present_flag", 1)),
    ("cropped_322x578", "322x578", ["-profile:v", "high", "-bf", "0"], False, ("frame_cropping_flag", 1)),
    ("four_refs", "320x576", ["-profile:v", "high", "-bf", "0", "-refs", "4"], False, ("max_num_ref_frames", 4)),
    ("interlaced", "320x576", ["-profile:v", "high", "-bf", "0", "-x264-params", "tff=1"], False,
     ("frame_mbs_only_flag", 0)),
]


def main():
    os.makedirs(CLASSES)
    r = run(["javac", "-proc:none", "-d", CLASSES, SPSFIX, DRIVER])
    if r.returncode != 0:
        print(quiet(r.stderr))
        return 1
    print("selftest:", java("selftest"))

    for name, size, vopts, bframes, must in CASES:
        print("== %s" % name)
        base = os.path.join(TMP, name + ".h264")
        g = run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=%s:rate=30:duration=1" % size,
                 "-c:v", "libx264", "-preset", "ultrafast", *vopts, "-f", "h264", base])
        if g.returncode != 0:
            print("  cannot generate:", g.stderr.strip())
            check(False, "%s: stream could not be generated" % name)
            continue
        data = open(base, "rb").read()
        if must:
            seen = val(sps_trace(base), must[0])
            check(seen == must[1], "%s: the test stream should have %s = %s but has %s (the case does not cover "
                  "what it is meant to)" % (name, must[0], must[1], seen))

        variants = {"as_written": base}
        for mode in ("strip", "strip-restriction"):
            p = os.path.join(TMP, "%s.%s.h264" % (name, mode))
            java(mode, base, p)
            variants[mode] = p
        if val(sps_trace(base), "chroma_format_idc") is not None:  # high profiles: also test scaling lists in the SPS
            p = os.path.join(TMP, "%s.craft-scaling.h264" % name)
            check(java("craft-scaling", base, p) == "scaling lists added", "%s: could not craft scaling lists" % name)
            check(val(sps_trace(p), "seq_scaling_matrix_present_flag") == 1,
                  "%s: crafted stream has no scaling matrix in the SPS" % name)
            variants["craft-scaling"] = p
            q = os.path.join(TMP, "%s.craft-scaling+strip.h264" % name)  # lists present, no VUI: forces the rewrite path
            java("strip", p, q)
            variants["craft-scaling+strip"] = q

        for vname, vpath in variants.items():
            tag = "%s/%s" % (name, vname)
            before = sps_trace(vpath)
            check(len(before) > 10, "%s: could not read the SPS with ffmpeg" % tag)
            if not before:
                continue

            # our parser's bit positions against ffmpeg's (the trace counts the 8-bit NAL header too)
            pline = java("positions", vpath).splitlines()[0]
            P = dict(kv.split("=") for kv in pline.split()[1:])
            check(int(P["vui_flag"]) + 8 == pos(before, "vui_parameters_present_flag"),
                  "%s: vui flag position %s vs ffmpeg %s" % (tag, P["vui_flag"], pos(before, "vui_parameters_present_flag")))
            if val(before, "vui_parameters_present_flag") == 1:
                check(int(P["restriction_flag"]) + 8 == pos(before, "bitstream_restriction_flag"),
                      "%s: restriction flag position" % tag)
            if val(before, "bitstream_restriction_flag") == 1:
                check(int(P["reorder_bit"]) + 8 == pos(before, "max_num_reorder_frames"), "%s: reorder position" % tag)
                check(int(P["reorder"]) == val(before, "max_num_reorder_frames"), "%s: reorder value" % tag)
                check(int(P["dpb"]) == val(before, "max_dec_frame_buffering"), "%s: dpb value" % tag)
            check(int(P["refs"]) == val(before, "max_num_ref_frames"), "%s: refs" % tag)

            fixed = os.path.join(TMP, "%s.%s.fixed.h264" % (name, vname))
            status = java("fix", vpath, fixed)
            good_already = (val(before, "bitstream_restriction_flag") == 1
                            and val(before, "max_num_reorder_frames") == 0
                            and val(before, "max_dec_frame_buffering") <= max(1, val(before, "max_num_ref_frames")))
            check(status == ("unchanged" if good_already else "patched"),
                  "%s: expected %s but SpsFix said %s" % (tag, "unchanged" if good_already else "patched", status))
            print("  %-18s %-9s  %s" % (vname, status, java("describe", fixed)))
            if status == "unchanged":
                continue

            after = sps_trace(fixed)
            check(core(before) == core(after), "%s: a field before the VUI changed" % tag)
            if val(before, "vui_parameters_present_flag") == 1:
                check(vui_head(before) == vui_head(after), "%s: a VUI field before the restriction flag changed" % tag)
            check(val(after, "vui_parameters_present_flag") == 1, "%s: no VUI after the fix" % tag)
            check(val(after, "bitstream_restriction_flag") == 1, "%s: no bitstream restriction after the fix" % tag)
            check(val(after, "max_num_reorder_frames") == 0, "%s: reorder is %s" % (tag, val(after, "max_num_reorder_frames")))
            want_dpb = max(1, val(after, "max_num_ref_frames"))
            check(val(after, "max_dec_frame_buffering") == want_dpb,
                  "%s: buffer is %s, wanted %s" % (tag, val(after, "max_dec_frame_buffering"), want_dpb))
            if val(before, "bitstream_restriction_flag") == 1:
                for nme in ("motion_vectors_over_pic_boundaries_flag", "max_bytes_per_pic_denom",
                            "max_bits_per_mb_denom", "log2_max_mv_length_horizontal", "log2_max_mv_length_vertical"):
                    check(val(before, nme) == val(after, nme), "%s: %s changed" % (tag, nme))

            # the pictures must come out the same (only comparable when there are no B-frames)
            m_before, e_before = framemd5(vpath)
            m_after, e_after = framemd5(fixed)
            check(e_after == "", "%s: ffmpeg complained about the fixed stream: %s" % (tag, e_after))
            if not bframes:
                check(m_before == m_after and len(m_after) > 0, "%s: decoded pictures differ after the fix" % tag)

        # chunking: fixing only the leading SPS+PPS gives the same bytes as fixing the whole stream
        p = variants["strip"]
        whole = open(p, "rb").read()
        first_vcl = next(o for (o, t) in nal_types(whole) if t in (1, 5))
        cfg = os.path.join(TMP, name + ".cfg.h264")
        open(cfg, "wb").write(whole[:first_vcl])
        cfg_fixed = os.path.join(TMP, name + ".cfg.fixed.h264")
        java("fix", cfg, cfg_fixed)
        whole_fixed = os.path.join(TMP, "%s.strip.fixed.h264" % name)
        check(open(whole_fixed, "rb").read() == open(cfg_fixed, "rb").read() + whole[first_vcl:],
              "%s: fixing the config chunk alone differs from fixing the whole stream" % name)

    print("\n%d checks, %d failed" % (checks, len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
