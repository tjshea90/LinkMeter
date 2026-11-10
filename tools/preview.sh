#!/usr/bin/env bash
# Draws the app's screens to PNG files (tools/uipreview/out/*.png) so they can be looked at without a device.
# See tools/uipreview/README.md. Not part of tools/test.sh or of the release pipeline.
set -e
cd "$(dirname "$0")/uipreview"
if command -v gradle >/dev/null 2>&1; then G=gradle; else G=../../gradlew; fi
"$G" --no-daemon -q :app:testDebugUnitTest "$@"
ls -1 out/*.png
