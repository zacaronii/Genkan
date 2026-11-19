#!/bin/sh
# Downloads LunaTranslator's x64 release and copies LunaHost64.dll, LunaHook32.dll and LunaHook64.dll
# into build/game/hookbridge. (LunaHook is GPL-3.0: https://github.com/HIllya51/LunaTranslator)
set -e
cd "$(dirname "$0")"
VER="${1:-v12.1.3.11}"
mkdir -p dl build/game/hookbridge
curl -sSL -o dl/lt_x64.zip "https://github.com/HIllya51/LunaTranslator/releases/download/$VER/LunaTranslator_x64.zip"
7z e -y -odl/lunahook dl/lt_x64.zip "LunaTranslator_x64/files/LunaHook/*" >/dev/null
cp dl/lunahook/LunaHost64.dll dl/lunahook/LunaHook32.dll dl/lunahook/LunaHook64.dll build/game/hookbridge/
echo "LunaHook $VER copied to build/game/hookbridge"
