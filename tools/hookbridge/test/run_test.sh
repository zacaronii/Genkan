#!/bin/sh
# End-to-end test under Wine: fakevn.exe + version.dll proxy + hookbridge-host.exe + a WebSocket listener.
cd "$(dirname "$0")/../build/${GAME_DIR:-game}"
export WINEPREFIX="${WINEPREFIX:-$HOME/.wine-hooktest}" WINEDEBUG=-all
[ -d "$WINEPREFIX" ] || wineboot -i >/dev/null 2>&1
python3 ../../test/listen.py ws://127.0.0.1:6677 120 > ../listener.log 2>&1 &
LISTENER=$!
WINEDLLOVERRIDES="version=n,b" timeout 150 xvfb-run -a wine ${GAME_EXE:-fakevn.exe} 2 > ../game.log 2>&1
sleep 2; kill $LISTENER 2>/dev/null; wineserver -k 2>/dev/null
echo "=== game"; cat ../game.log
echo "=== host"; cat hookbridge/hookbridge-host.log 2>/dev/null
echo "=== listener"; cat ../listener.log
