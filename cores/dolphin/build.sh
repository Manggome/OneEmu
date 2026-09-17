#!/usr/bin/env bash
# OneEmu core build: Dolphin (GameCube / Wii) libretro core, libretro/dolphin fork
#                    -> arm64-v8a libdolphin_libretro.so  (downloadable core, core.json "distribution": "download")
#
# Usage (from project root):  bash cores/dolphin/build.sh [--clean]
# Works on macOS (arm64/x86_64) and Ubuntu (GitHub Actions).
#
# Notes / deviations from the generic convention, all deliberate:
#  * Dolphin requires CMake >= 3.25 (cmake_minimum_required 3.25...4.2.1); the SDK's cmake/3.22.1 cannot
#    configure it. Same approach as cores/azaharplus: a cmake >= 3.25 from PATH if present, otherwise one
#    pip-installed into a venv under build/. Ninja still comes from the SDK cmake/3.22.1 dir (or PATH).
#  * The fork's own Android CI (.github/workflows/build-android-libretro.yml) configures with exactly
#    -DLIBRETRO=ON -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 (NDK r29).
#    We use the project NDK (r28.2) and build with ENABLE_VULKAN=ON: the frontend answers GET_PREFERRED_HW_RENDER
#    with RETRO_HW_CONTEXT_VULKAN for this core (core.json hwRender = "vulkan"), so Dolphin's Vulkan backend is used;
#    Video.cpp falls back to OGL when the frontend offers GLES instead.
#    LIBRETRO=ON itself forces Qt/NoGUI/tests/SDL/evdev/ALSA/Pulse/analytics/autoupdate/cubeb/discord/
#    RetroAchievements/UPnP off (top-level CMakeLists.txt).
#  * Recursive clone (~30 submodules, ~1.1 GB with shallow submodules).
#  * Runtime assets: the core reads <libretro system dir>/dolphin-emu/Sys/ (Source/Core/DolphinLibretro/
#    Boot.cpp). The subset it actually uses is copied from src/Data/Sys into cores/dolphin/assets/ (installed by
#    the app via core.json assetsInstallDir = "dolphin-emu/Sys"). Everything in there is Dolphin's own freely
#    licensed replacement data (Droid Sans based IPL fonts, free DSP ROM, Gecko code handler, per-game INIs,
#    post-process shaders) - no Nintendo IPL/BIOS dump is included or required.
set -euo pipefail

CORE_ID="dolphin"
REPO_URL="https://github.com/libretro/dolphin"
COMMIT="ed70219e8bf86717d505b56a830d14ee7e7acce5"      # 2026-09-14 master "Merge pull request #496 from cscd98/android" (Dolphin 2606-based)
NDK_VERSION="28.2.13676358"
API_LEVEL=26
ABI="arm64-v8a"
CMAKE_MIN="3.25"
CMAKE_PIP_SPEC="cmake>=3.30,<4.5"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
CMAKE_BUILD_DIR="$BUILD_DIR/android-$ABI"
VENV_DIR="$BUILD_DIR/venv"
PATCH_DIR="$SCRIPT_DIR/patches"
ASSET_OUT_DIR="$SCRIPT_DIR/assets"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/$ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

log() { printf '\033[1;34m[%s]\033[0m %s\n' "$CORE_ID" "$*"; }
die() { printf '\033[1;31m[%s] ERROR:\033[0m %s\n' "$CORE_ID" "$*" >&2; exit 1; }

CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

# ---------------------------------------------------------------- NDK lookup
find_ndk() {
  local c
  for c in "${ANDROID_NDK_HOME:-}" \
           "${ANDROID_HOME:-}/ndk/$NDK_VERSION" \
           "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VERSION" \
           "$HOME/Library/Android/sdk/ndk/$NDK_VERSION" \
           "$HOME/Android/Sdk/ndk/$NDK_VERSION"; do
    if [ -n "$c" ] && [ -f "$c/build/cmake/android.toolchain.cmake" ]; then echo "$c"; return 0; fi
  done
  return 1
}
NDK="$(find_ndk)" || die "NDK $NDK_VERSION not found (set ANDROID_NDK_HOME)"

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;   # NDK ships a universal darwin-x86_64 prebuilt (runs on arm64 Macs)
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) die "unsupported host $(uname -s)" ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$LLVM_BIN" ] || die "toolchain not found at $LLVM_BIN"
STRIP="$LLVM_BIN/llvm-strip"
READELF="$LLVM_BIN/llvm-readelf"
NM="$LLVM_BIN/llvm-nm"

# ---------------------------------------------------------------- Ninja (SDK cmake dir first, then PATH)
SDK_CMAKE_BIN=""
for c in "${ANDROID_HOME:-}/cmake/3.22.1/bin" \
         "${ANDROID_SDK_ROOT:-}/cmake/3.22.1/bin" \
         "$HOME/Library/Android/sdk/cmake/3.22.1/bin" \
         "$HOME/Android/Sdk/cmake/3.22.1/bin"; do
  if [ -n "$c" ] && [ -x "$c/ninja" ]; then SDK_CMAKE_BIN="$c"; break; fi
done
if [ -n "$SDK_CMAKE_BIN" ]; then
  NINJA="$SDK_CMAKE_BIN/ninja"
else
  NINJA="$(command -v ninja || true)"
  [ -n "$NINJA" ] || die "ninja not found (SDK cmake/3.22.1 or PATH)"
fi

# ---------------------------------------------------------------- CMake >= 3.25
cmake_is_new_enough() {
  local v
  v="$("$1" --version 2>/dev/null | head -n1 | sed -E 's/^cmake version ([0-9]+\.[0-9]+).*/\1/')" || return 1
  [ -n "$v" ] || return 1
  [ "$(printf '%s\n%s\n' "$CMAKE_MIN" "$v" | sort -t. -k1,1n -k2,2n | head -n1)" = "$CMAKE_MIN" ]
}

if [ "$CLEAN" = 1 ]; then rm -rf "$BUILD_DIR"; fi
mkdir -p "$BUILD_DIR"

CMAKE=""
PATH_CMAKE="$(command -v cmake || true)"
if [ -n "$PATH_CMAKE" ] && cmake_is_new_enough "$PATH_CMAKE"; then
  CMAKE="$PATH_CMAKE"
elif [ -x "$VENV_DIR/bin/cmake" ] && cmake_is_new_enough "$VENV_DIR/bin/cmake"; then
  CMAKE="$VENV_DIR/bin/cmake"
else
  log "No cmake >= $CMAKE_MIN on PATH; installing '$CMAKE_PIP_SPEC' into $VENV_DIR"
  PYTHON="$(command -v python3 || true)"
  [ -n "$PYTHON" ] || [ ! -x /opt/homebrew/bin/python3 ] || PYTHON=/opt/homebrew/bin/python3
  [ -n "$PYTHON" ] || die "python3 not found (needed to pip-install cmake >= $CMAKE_MIN)"
  rm -rf "$VENV_DIR"
  "$PYTHON" -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install --quiet --disable-pip-version-check "$CMAKE_PIP_SPEC"
  CMAKE="$VENV_DIR/bin/cmake"
  cmake_is_new_enough "$CMAKE" || die "pip cmake is still < $CMAKE_MIN"
fi

if command -v nproc >/dev/null 2>&1; then JOBS="${JOBS:-$(nproc)}"; else JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || echo 4)}"; fi

# ccache (optional): several-minute re-runs instead of ~40 min.
LAUNCHER_ARGS=()
if command -v ccache >/dev/null 2>&1; then
  export CCACHE_DIR="${CCACHE_DIR:-$BUILD_DIR/ccache}"
  export CCACHE_BASEDIR="$SRC_DIR"
  export CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-20G}"
  export CCACHE_SLOPPINESS="time_macros,include_file_mtime,include_file_ctime"
  LAUNCHER_ARGS=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
  log "ccache: $(command -v ccache) (dir $CCACHE_DIR)"
else
  log "ccache not found: full rebuilds every time (brew install ccache / apt install ccache)"
fi

log "Dolphin (GameCube/Wii) libretro core build"
log "  NDK:     $NDK ($HOST_TAG)"
log "  CMake:   $CMAKE ($("$CMAKE" --version | head -n1))"
log "  Ninja:   $NINJA"
log "  Commit:  $COMMIT"
log "  Jobs:    $JOBS"
log "  Output:  $OUT_SO"

# ---------------------------------------------------------------- source checkout (pinned, recursive)
if [ ! -d "$SRC_DIR/.git" ]; then
  log "Cloning $REPO_URL (shallow)"
  rm -rf "$SRC_DIR"
  git clone --depth 1 "$REPO_URL" "$SRC_DIR"
fi
if [ "$(git -C "$SRC_DIR" rev-parse HEAD)" != "$COMMIT" ]; then
  log "Checking out $COMMIT"
  git -C "$SRC_DIR" fetch --quiet --depth 1 origin "$COMMIT" 2>/dev/null || git -C "$SRC_DIR" fetch --quiet --unshallow origin
  git -C "$SRC_DIR" checkout --quiet --force --detach "$COMMIT"
  # Tree changed: force a fresh configure.
  rm -rf "$CMAKE_BUILD_DIR"
fi
# Submodules (shallow); also covers a src/ that was pre-populated at the right commit without them.
log "Syncing submodules (shallow)"
git -C "$SRC_DIR" submodule update --init --recursive --depth 1

# ---------------------------------------------------------------- patches (idempotent)
shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
for p in ${PATCHES[@]+"${PATCHES[@]}"}; do
  if git -C "$SRC_DIR" apply --check --reverse "$p" >/dev/null 2>&1; then
    log "Patch already applied: $(basename "$p")"
  elif git -C "$SRC_DIR" apply --check "$p" >/dev/null 2>&1; then
    log "Applying patch: $(basename "$p")"
    git -C "$SRC_DIR" apply "$p"
  else
    die "patch $(basename "$p") does not apply to $COMMIT"
  fi
done

# ---------------------------------------------------------------- configure
mkdir -p "$CMAKE_BUILD_DIR" "$OUT_DIR"
"$CMAKE" -S "$SRC_DIR" -B "$CMAKE_BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="$NDK" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DLIBRETRO=ON \
  -DENABLE_VULKAN=ON \
  ${LAUNCHER_ARGS[@]+"${LAUNCHER_ARGS[@]}"}

# ---------------------------------------------------------------- build
START_TS=$(date +%s)
log "Building dolphin_libretro (-j$JOBS)"
"$CMAKE" --build "$CMAKE_BUILD_DIR" --target dolphin_libretro -j "$JOBS"
log "build finished in $(( $(date +%s) - START_TS ))s"
command -v ccache >/dev/null 2>&1 && ccache -s | head -6 || true

# Source/Core/DolphinLibretro/CMakeLists.txt: PREFIX "" + SUFFIX "_android.so", LIBRARY_OUTPUT_PATH = build root.
BUILT_SO="$CMAKE_BUILD_DIR/dolphin_libretro_android.so"
[ -f "$BUILT_SO" ] || BUILT_SO="$(find "$CMAKE_BUILD_DIR" -maxdepth 3 -name 'dolphin_libretro*.so' -type f -print -quit)"
[ -n "$BUILT_SO" ] && [ -f "$BUILT_SO" ] || die "dolphin_libretro_android.so not produced under $CMAKE_BUILD_DIR"
log "built: $BUILT_SO ($(du -h "$BUILT_SO" | cut -f1) unstripped)"

# ---------------------------------------------------------------- strip + install
log "Stripping"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$BUILT_SO"

# ---------------------------------------------------------------- runtime assets -> cores/dolphin/assets
# Installed by the app to <system dir>/dolphin-emu/Sys (core.json assetsInstallDir). Only what the core reads:
#   GC/            IPL font replacements (Droid Sans, Apache-2.0) + free DSP ROM/coefs (EXI_DeviceIPL, DSPHLE AX)
#   Wii/           sysmenu shared2 files copied into the emulated NAND on first Wii boot (WiiRoot.cpp)
#   Shaders/       post-process shaders incl. default_pre_post_process.glsl (PostProcessing.cpp)
#   Profiles/      bundled input profiles (InputConfig.cpp)
#   Resources/OSD_Font.ttf   imgui OSD font (OnScreenUI.cpp)
#   GameSettings/  per-game INIs, GameCube titles only (IDs G*/D*/P*; Wii INIs would add ~5 MB)
#   codehandler.bin          Gecko code handler; the core logs a "core files missing" error without it
# Skipped: totaldb.dsy (DolphinQt debugger only), wiitdb-*.txt (title database, UI only), Themes/, Load/,
# Resources/* other than the OSD font, ApprovedInis.json (RetroAchievements, disabled).
log "Copying runtime assets -> $ASSET_OUT_DIR"
SYS="$SRC_DIR/Data/Sys"
rm -rf "$ASSET_OUT_DIR"
mkdir -p "$ASSET_OUT_DIR/Resources" "$ASSET_OUT_DIR/GameSettings"
cp -R "$SYS/GC" "$SYS/Wii" "$SYS/Shaders" "$SYS/Profiles" "$ASSET_OUT_DIR/"
cp "$SYS/codehandler.bin" "$ASSET_OUT_DIR/"
cp "$SYS/Resources/OSD_Font.ttf" "$ASSET_OUT_DIR/Resources/"
find "$SYS/GameSettings" -maxdepth 1 -type f \( -name 'G*.ini' -o -name 'D*.ini' -o -name 'P*.ini' \) -exec cp {} "$ASSET_OUT_DIR/GameSettings/" \;
find "$ASSET_OUT_DIR" -name '.DS_Store' -delete

# ---------------------------------------------------------------- verify
# (capture output first: with pipefail, `readelf | grep -q` can fail on SIGPIPE)
log "Verifying"
ELF_HEADER="$("$READELF" -h "$OUT_SO")"
grep -q "AArch64" <<<"$ELF_HEADER" || die "not an AArch64 ELF"
DYN_SYMS="$("$NM" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  grep -qE " T ${sym}$" <<<"$DYN_SYMS" || die "missing exported symbol $sym"
done
# Every LOAD segment must be 16 KB aligned (Android 15 requirement).
LOAD_ALIGNS="$("$READELF" -l "$OUT_SO" | awk '/^  LOAD/ {print $NF}')"
if grep -qvE '^0x(4000|10000)$' <<<"$LOAD_ALIGNS"; then
  die "LOAD segments are not 16 KB aligned: $LOAD_ALIGNS"
fi
# Only bionic libraries may be NEEDED (libc++ is linked statically).
NEEDED="$("$READELF" -d "$OUT_SO" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}')"
for lib in $NEEDED; do
  case "$lib" in
    libc.so|libm.so|libdl.so|libz.so|liblog.so|libandroid.so|libGLESv3.so|libGLESv2.so|libEGL.so|libOpenSLES.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done
log "  Machine : $(grep Machine <<<"$ELF_HEADER" | sed 's/.*: *//')"
log "  Exports : $(grep -cE " T retro_" <<<"$DYN_SYMS") retro_* symbols"
log "  NEEDED  : $(echo $NEEDED)"
log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)), assets: $(du -sh "$ASSET_OUT_DIR" | cut -f1)"
