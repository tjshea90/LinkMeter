#!/usr/bin/env bash
# Offline test of SpsFix (the H.264 header fix) against real x264 streams and ffmpeg's own parser.
cd "$(dirname "$0")/.." && exec python3 tools/test/test_spsfix.py "$@"
