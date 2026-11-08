#!/usr/bin/env bash
# Offline test of AnnexBFramer (the tablet's H.264 picture cutter) against real x264 streams and ffprobe.
cd "$(dirname "$0")/.." && exec python3 tools/test/test_framer.py "$@"
