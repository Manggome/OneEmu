#!/usr/bin/env bash
# OneEmu core build: ARMSX2 (PlayStation 2) libretro core
#                    -> arm64-v8a libarmsx2_libretro.so  (downloadable core, core.json "distribution": "download")
#
# Usage (from project root):  bash cores/armsx2/build.sh [--clean]
#
# ARMSX2 is the ARM64 fork of PCSX2: upstream PCSX2 only has x86-64 recompilers and falls back to an
# interpreter on ARM, which is why Play! was the only usable PS2 core here before. ARMSX2 adds native
# AArch64 JITs (EE, IOP, VU0/VU1) and ships an Android libretro target (pcsx2-libretro, OUTPUT_NAME
# "armsx2_libretro"), so it is built the same way as any other downloadable core here.
#
# Notes / deviations from the generic convention, all deliberate:
#  * Requires a real PS2 BIOS dump (no HLE): the app looks for it under <system dir>/pcsx2/bios,
#    which is where the core reads it (pcsx2-libretro/Main.cpp: EmuFolders::AppRoot = <system>/pcsx2).
#  * HOST_PAGE_SIZE: PCSX2's vtlb/fastmem maps memory at host page granularity. Android 15+ devices use
#    16 KB pages, older ones 4 KB; a 16 KB assumption is also valid on a 4 KB kernel (it is a multiple),
#    so one core serves both instead of the two variants the ARMSX2 app ships.
#  * DISABLE_ADVANCE_SIMD / OVERRIDE_HOST_PAGE_SIZE / no precompiled headers mirror the flags ARMSX2's
#    own libretro CI uses (.github/workflows/linux_build_libretro.yml).
#  * Runtime resources (fonts, GS shaders, per-game compatibility fixes) are copied to cores/armsx2/assets
#    and installed by the app as <system dir>/pcsx2/resources (core.json assetsInstallDir).
set -euo pipefail

CORE_ID="armsx2"
REPO_URL="https://github.com/ARMSX2/ARMSX2"
COMMIT="f4272b6768a2695bfda9265c319e595509cdc54c"      # 2026-09-17 master "Common: one Darwin clock and sleep path for iOS and macOS"
HOST_PAGE_SIZE=16384                                   # see the header note
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

log "ARMSX2 (PlayStation 2) libretro core build"
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
  rm -rf "$CMAKE_BUILD_DIR"
fi
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

# ---------------------------------------------------------------- shaderc dependencies
# PCSX2's Vulkan renderer compiles its shaders with shaderc, whose own dependencies (SPIRV-Tools,
# SPIRV-Headers, glslang) are not submodules but fetched by the script shaderc ships. The Android app
# build runs it through Gradle; here it has to be run explicitly or configure stops at
# "SPIRV-Tools was not found".
SHADERC_DIR="$SRC_DIR/platforms/android/app/src/main/cpp/3rdparty/shaderc"
if [ -f "$SHADERC_DIR/utils/git-sync-deps" ] && [ ! -d "$SHADERC_DIR/third_party/spirv-tools" ]; then
  log "Fetching shaderc dependencies (SPIRV-Tools, SPIRV-Headers, glslang)"
  PY="$(command -v python3 || command -v python)" || die "python needed for shaderc's git-sync-deps"
  (cd "$SHADERC_DIR" && "$PY" ./utils/git-sync-deps) || die "git-sync-deps failed"
fi

# PCSX2 embeds resources (GameIndex.yaml, shaders) with paths relative to the CMake source dir, which
# here is the Android cpp/ folder; the files themselves live in the repository's bin/. Gradle stages them,
# so outside Gradle the link has to be made by hand.
ANDROID_CPP_DIR="$SRC_DIR/platforms/android/app/src/main/cpp"
if [ ! -e "$ANDROID_CPP_DIR/bin" ]; then
  ln -s "$SRC_DIR/bin" "$ANDROID_CPP_DIR/bin"
  log "linked $ANDROID_CPP_DIR/bin -> $SRC_DIR/bin"
fi
# Same for the CMake modules: the Android CMakeLists points CMAKE_MODULE_PATH at its own cmake/ folder,
# which carries the Android variants but not every module the shared subdirectories include
# (EmbedResources.cmake lives only in the repository's cmake/).
for m in "$SRC_DIR"/cmake/*.cmake; do
  [ -e "$ANDROID_CPP_DIR/cmake/$(basename "$m")" ] || ln -s "$m" "$ANDROID_CPP_DIR/cmake/$(basename "$m")"
done

# ---------------------------------------------------------------- configure
mkdir -p "$CMAKE_BUILD_DIR" "$OUT_DIR"
# The Android app's CMakeLists, not the root one: it resolves every dependency from the vendored
# 3rdparty tree instead of desktop find_package (which would look for PNG/SDL3/Zstd in the NDK sysroot).
"$CMAKE" -S "$SRC_DIR/platforms/android/app/src/main/cpp" -B "$CMAKE_BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="$NDK" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DANDROID_STL=c++_static \
  -DANDROID=true \
  -DCMAKE_BUILD_TYPE=Release \
  -DARMSX2_BUILD_LIBRETRO=ON \
  -DARMSX2_EMUCORE_LIBRARY_NAME=emucore \
  -DENABLE_LIBRETRO=ON \
  -DENABLE_TESTS=OFF \
  -DENABLE_RECOMPILER_TEST_HOOKS=OFF \
  -DUSE_BACKTRACE=OFF \
  -DENABLE_VULKAN=ON \
  -DDISABLE_ADVANCE_SIMD=TRUE \
  -DLTO_PCSX2_CORE=OFF \
  -DOVERRIDE_HOST_PAGE_SIZE=$HOST_PAGE_SIZE \
  -DARMSX2_ANDROID_HOST_PAGE_SIZE=$HOST_PAGE_SIZE \
  -DCMAKE_DISABLE_PRECOMPILE_HEADERS=ON \
  ${LAUNCHER_ARGS[@]+"${LAUNCHER_ARGS[@]}"}

# ---------------------------------------------------------------- build
START_TS=$(date +%s)
log "Building pcsx2-libretro (-j$JOBS)"
"$CMAKE" --build "$CMAKE_BUILD_DIR" --target pcsx2-libretro -j "$JOBS"
log "build finished in $(( $(date +%s) - START_TS ))s"
command -v ccache >/dev/null 2>&1 && ccache -s | head -6 || true

# pcsx2-libretro/CMakeLists.txt: OUTPUT_NAME "armsx2_libretro", PREFIX "" (libretro naming).
BUILT_SO="$(find "$CMAKE_BUILD_DIR" -name 'armsx2_libretro*.so' -type f -print -quit)"
[ -n "$BUILT_SO" ] && [ -f "$BUILT_SO" ] || die "armsx2_libretro.so not produced under $CMAKE_BUILD_DIR"
log "built: $BUILT_SO ($(du -h "$BUILT_SO" | cut -f1) unstripped)"

# ---------------------------------------------------------------- strip + install
log "Stripping"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$BUILT_SO"

# ---------------------------------------------------------------- runtime resources -> cores/armsx2/assets
# Installed by the app as <system dir>/pcsx2/resources (core.json assetsInstallDir). Only what the core reads:
#   fonts/         Roboto-Regular.ttf, required by ImGuiManager (Main.cpp) even when no OSD is drawn
#   shaders/       GS shaders (Vulkan/GL); the renderer cannot start without them
#   GameIndex.yaml per-game compatibility fixes PCSX2 applies automatically
# Skipped: RedumpDatabase.yaml, game_controller_db.txt, icons/, sounds/, fullscreenui/, cover-placeholder.png
# (desktop UI only, ~2.5 MB saved).
log "Copying runtime resources -> $ASSET_OUT_DIR"
RES="$SRC_DIR/bin/resources"
[ -d "$RES" ] || die "resources not found at $RES"
rm -rf "$ASSET_OUT_DIR"
mkdir -p "$ASSET_OUT_DIR"
cp -R "$RES/fonts" "$RES/shaders" "$ASSET_OUT_DIR/"
cp "$RES/GameIndex.yaml" "$ASSET_OUT_DIR/"
find "$ASSET_OUT_DIR" -name '.DS_Store' -delete

# ---------------------------------------------------------------- verify
log "Verifying"
ELF_HEADER="$("$READELF" -h "$OUT_SO")"
grep -q "AArch64" <<<"$ELF_HEADER" || die "not an AArch64 ELF"
DYN_SYMS="$("$NM" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  grep -qE " T ${sym}$" <<<"$DYN_SYMS" || die "missing exported symbol $sym"
done
LOAD_ALIGNS="$("$READELF" -l "$OUT_SO" | awk '/^  LOAD/ {print $NF}')"
if grep -qvE '^0x(4000|10000)$' <<<"$LOAD_ALIGNS"; then
  die "LOAD segments are not 16 KB aligned: $LOAD_ALIGNS"
fi
NEEDED="$("$READELF" -d "$OUT_SO" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}')"
for lib in $NEEDED; do
  case "$lib" in
    libc.so|libm.so|libdl.so|libz.so|liblog.so|libandroid.so|libGLESv3.so|libGLESv2.so|libEGL.so|libOpenSLES.so|libvulkan.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done
log "  Machine : $(grep Machine <<<"$ELF_HEADER" | sed 's/.*: *//')"
log "  Exports : $(grep -cE " T retro_" <<<"$DYN_SYMS") retro_* symbols"
log "  NEEDED  : $(echo $NEEDED)"
log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)), resources: $(du -sh "$ASSET_OUT_DIR" | cut -f1)"
