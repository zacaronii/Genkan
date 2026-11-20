#!/bin/sh
# Builds the hook kit Genkan installs into Android's Download/Genkan/hook (drive D: in
# GameNative): LunaHook, the host, both version.dll proxies and the batch files that copy the
# right proxy next to a game's .exe. Output: build/assets/hookbridge/ (Genkan's build packs it).
# Needs MinGW and unzip; fetches LunaHook the first time.
set -e
cd "$(dirname "$0")"
[ -f build/game/hookbridge/LunaHost64.dll ] || ./get_lunahook.sh
./build.sh >/dev/null
KIT=build/assets/hookbridge
rm -rf "$KIT"
mkdir -p "$KIT"
cp build/game/hookbridge/LunaHost64.dll build/game/hookbridge/LunaHook32.dll build/game/hookbridge/LunaHook64.dll \
    build/game/hookbridge/hookbridge-host.exe "$KIT/"
cp build/game/version.dll "$KIT/version32.dll"
cp build/game64/version.dll "$KIT/version64.dll"

# Run from the game's "Open container" desktop, where A: is the game's folder. setup.bat
# guesses 32- or 64-bit from the folder's contents; the other two force one.
crlf() { sed 's/$/\r/'; }
crlf > "$KIT/setup-32bit.bat" <<'EOF'
@echo off
copy /y "%~dp0version32.dll" A:\version.dll > "%~dp0setup.log" 2>&1
type "%~dp0setup.log"
pause
EOF
crlf > "$KIT/setup-64bit.bat" <<'EOF'
@echo off
copy /y "%~dp0version64.dll" A:\version.dll > "%~dp0setup.log" 2>&1
type "%~dp0setup.log"
pause
EOF
crlf > "$KIT/setup.bat" <<'EOF'
@echo off
rem 64-bit if the game folder looks like a 64-bit Unity or Unreal build, else 32-bit.
set BITS=32
if exist A:\UnityCrashHandler64.exe set BITS=64
if exist A:\Engine\Binaries\Win64 set BITS=64
for /d %%d in (A:\*_Data) do if exist "%%d\Plugins\x86_64" set BITS=64
echo Installing the %BITS%-bit hook into the game folder (A:)
call "%~dp0setup-%BITS%bit.bat"
EOF
# Genkan reinstalls the kit when this changes.
(cd "$KIT" && cat ./*.dll ./*.exe ./*.bat | shasum | cut -c1-16) > "$KIT/kit.version"
echo "hook kit in $KIT:" && ls "$KIT"
