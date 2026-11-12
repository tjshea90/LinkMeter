#!/usr/bin/env bash
# Fast Wi-Fi end to end on a REAL Linux tun device: the tablet's engine (TunStack) with the Linux kernel's own TCP/UDP as the apps,
# the phone's relay (FastRelayCore), test servers as the internet (tools/test/FastE2E.java, tools/test/fast_tun_bridge.py).
# Needs root and /dev/net/tun (the cloud sandbox has both); prints SKIPPED and exits 0 where they are missing (GitHub's runner),
# so it can be called from anywhere. Takes about half a minute.
set -u -o pipefail
cd "$(dirname "$0")/.." || exit 1
if [ "$(id -u)" != 0 ] || [ ! -c /dev/net/tun ]; then
  echo "SKIPPED: the Fast Wi-Fi end-to-end test needs root and /dev/net/tun"
  exit 0
fi
out=$(mktemp -d)
FM=app/src/main/java/com/tj/linkmeter
javac -proc:none -encoding UTF-8 -d "$out" $FM/Ip4.java $FM/FastProto.java $FM/TunStack.java $FM/FastRelayCore.java \
  tools/test/FastE2E.java || exit 1
timeout 300 java -cp "$out" com.tj.linkmeter.FastE2E
rc=$?
rm -rf "$out"
exit $rc
