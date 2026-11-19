#!/usr/bin/env bash
# Hands Home back to the stock launcher if Genkan misbehaves as the default home.
#
#   scripts/recover-home.sh [serial] [stock-home-component]
#
# serial defaults to $ANDROID_SERIAL (or the only connected device; pass "" to skip it).
# With no component the script releases Genkan's home entries and lists the home activities
# the device offers, so you can rerun it with the stock launcher's component, for example
#   scripts/recover-home.sh "" com.android.launcher3/.uioverrides.QuickstepLauncher
# Keep wireless adb authorised before enabling Genkan as home on a device.
set -euo pipefail

PKG=app.genkan
serial="${1:-${ANDROID_SERIAL:-}}"
stock="${2:-}"
adb_cmd=(adb)
[[ -n "$serial" ]] && adb_cmd+=(-s "$serial")

run() { "${adb_cmd[@]}" shell "$@"; }

# Since Android 13 the shell may not toggle another app's components, so ask Genkan to
# disable its own HOME alias and companion. If Genkan can't answer, disable the package.
echo "Asking Genkan to release Home..."
if run am broadcast -n "$PKG/.home.RecoveryReceiver" -a "$PKG.action.RELEASE_HOME" | grep -q "result=1"; then
  echo "Genkan released its home entries."
else
  echo "Genkan did not answer; disabling the whole package (undo: adb shell pm enable $PKG)."
  run pm disable-user --user 0 "$PKG"
fi

if [[ -n "$stock" ]]; then
  echo "Setting $stock as home..."
  run cmd package set-home-activity --user 0 "$stock"
else
  echo "Home activities on the device:"
  run cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
fi

run input keyevent KEYCODE_HOME
echo "Done. Re-enable from Genkan's setup screen (open it from the app drawer)."
