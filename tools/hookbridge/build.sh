#!/bin/sh
# Builds the test VN, the version.dll proxy (32-bit) and the host (64-bit) with MinGW.
set -e
cd "$(dirname "$0")"
mkdir -p build/game/hookbridge
i686-w64-mingw32-gcc -O2 -o build/game/fakevn.exe src/fakevn.c -lversion -lgdi32 -luser32
i686-w64-mingw32-gcc -O2 -shared -o build/game/version.dll src/version_proxy.c src/version.def -static-libgcc
x86_64-w64-mingw32-gcc -O2 -municode -o build/game/hookbridge/hookbridge-host.exe src/hookbridge_host.c -lws2_32 -static-libgcc
echo built: && ls -la build/game build/game/hookbridge
# 64-bit games need a 64-bit proxy (it loads LunaHook64.dll instead).
mkdir -p build/game64
x86_64-w64-mingw32-gcc -O2 -shared -o build/game64/version.dll src/version_proxy.c src/version.def -static-libgcc
x86_64-w64-mingw32-gcc -O2 -o build/game64/fakevn64.exe src/fakevn.c -lversion -lgdi32 -luser32
# The 64-bit test gets its own copy of the kit (fetch LunaHook first: ./get_lunahook.sh).
if [ -f build/game/hookbridge/LunaHost64.dll ]; then cp -R build/game/hookbridge build/game64/; fi
