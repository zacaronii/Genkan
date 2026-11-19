#!/usr/bin/env bash
# Adds a Thor-sized (1080x1240) secondary display to a running Android Emulator, so the
# SECONDARY_HOME companion can be exercised without hardware.
#
#   scripts/emulator-secondary-display.sh [serial]   # serial defaults to $ANDROID_SERIAL
set -euo pipefail

serial="${1:-${ANDROID_SERIAL:-}}"
adb_cmd=(adb)
[[ -n "$serial" ]] && adb_cmd+=(-s "$serial")

# multidisplay add <slot 1..10> <width> <height> <dpi> <flags>. A headless emulator may answer
# the first call with "KO: setMultiDisplay not supported" yet add the display, so the result
# is checked in dumpsys rather than trusted. Android assigns a new display id on every add.
"${adb_cmd[@]}" emu multidisplay add 1 1080 1240 240 0 || true
sleep 2
if "${adb_cmd[@]}" shell dumpsys display | grep -q "Emulator 2D Display"; then
  "${adb_cmd[@]}" shell dumpsys display | grep -oE 'DisplayInfo\{"Emulator 2D Display", displayId [0-9]+' | sort -u
else
  echo "No secondary display was added" >&2
  exit 1
fi
