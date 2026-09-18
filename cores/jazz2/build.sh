#!/usr/bin/env bash
# OneEmu core build: Jazz² Resurrection (Jazz Jackrabbit 2 reimplementation) libretro core
#                    -> arm64-v8a libjazz2_libretro.so  (bundled core)
#
# Usage (from project root):  bash cores/jazz2/build.sh [--clean]
#
# Not an emulator: jazz2-native is an open-source rewrite of the 1998 Windows game that reads the original
# game's data files. It ships a libretro backend of its own (Sources/libretro.cpp,
# Sources/nCine/Backends/Libretro), so it drops into this app like any other core - which is the only way
# to play Jazz Jackrabbit 2 here, the original being a Win32 x86 program no console core can run.
#
# Notes:
#  * -DNCINE_BUILD_LIBRETRO=ON turns the app into a shared library named <app>_libretro.so with no window
#    backend (CMakeLists.txt:114); the NDK toolchain otherwise builds it like any Android target.
#  * Content/ (Animations, Metadata, Translations) is the engine's own data and must be installed as
#    <system dir>/jazz2/Content - the core starts with no content at all when it is there (libretro.cpp:97).
#  * The user's original Jazz Jackrabbit 2 files go to <system dir>/jazz2/Source/ (README: "Copy contents of
#    original Jazz Jackrabbit 2 directory to ...Source/"). Nothing copyrighted is shipped here.
set -euo pipefail

CORE_ID="jazz2"
REPO_URL="https://github.com/deathkiller/jazz2-native"
COMMIT="9cc77a769ca0deb82c664dce733c1fd1173b6235"
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

log "Jazz² Resurrection (Jazz Jackrabbit 2) libretro core build"
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

# ---------------------------------------------------------------- OpenAL Soft (audio)
# nCine renders the game's audio into the libretro buffer through OpenAL Soft's loopback device
# (Sources/nCine/Audio/Backends/AL), and imports it on Android as a prebuilt at
# ${EXTERNAL_ANDROID_DIR}/${ANDROID_ABI}/libopenal.so. The NDK has no such library, so it is built here;
# without it the core runs silent (libretro.cpp fills the frontend's buffer with zeroes).
OPENAL_REPO="https://github.com/kcat/openal-soft"
OPENAL_TAG="1.24.3"
OPENAL_SRC="$BUILD_DIR/openal-soft"
EXT_DIR="$BUILD_DIR/external"
if [ ! -f "$EXT_DIR/$ABI/libopenal.so" ]; then
  log "Building OpenAL Soft $OPENAL_TAG"
  [ -d "$OPENAL_SRC/.git" ] || git clone --depth 1 --branch "$OPENAL_TAG" "$OPENAL_REPO" "$OPENAL_SRC"
  "$CMAKE" -S "$OPENAL_SRC" -B "$BUILD_DIR/openal-build" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DLIBTYPE=SHARED \
    -DALSOFT_UTILS=OFF \
    -DALSOFT_EXAMPLES=OFF \
    -DALSOFT_TESTS=OFF \
    -DALSOFT_INSTALL=OFF \
    ${LAUNCHER_ARGS[@]+"${LAUNCHER_ARGS[@]}"}
  "$CMAKE" --build "$BUILD_DIR/openal-build" -j "$JOBS"
  BUILT_AL="$(find "$BUILD_DIR/openal-build" -name 'libopenal.so' -type f -print -quit)"
  [ -n "$BUILT_AL" ] || die "libopenal.so not produced"
  mkdir -p "$EXT_DIR/$ABI" "$EXT_DIR/include/AL"
  cp "$BUILT_AL" "$EXT_DIR/$ABI/libopenal.so"
  cp "$OPENAL_SRC"/include/AL/*.h "$EXT_DIR/include/AL/"
fi
log "OpenAL: $EXT_DIR/$ABI/libopenal.so ($(du -h "$EXT_DIR/$ABI/libopenal.so" | cut -f1))"

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
  -DNCINE_BUILD_LIBRETRO=ON \
  -DNCINE_PREFERRED_RHI=OpenGL \
  -DWITH_WEBSOCKET=OFF \
  -DNCINE_BUILD_ANDROID=OFF \
  -DNCINE_BUILD_SHADER_COMPILER=OFF \
  -DNCINE_WITH_AUDIO=ON \
  -DNCINE_WITH_IMGUI=OFF \
  -DWITH_MULTIPLAYER=OFF \
  -DCMAKE_C_FLAGS="-I$NDK/sources/android/native_app_glue" \
  -DCMAKE_CXX_FLAGS="-I$NDK/sources/android/native_app_glue -I$EXT_DIR/include" \
  -DEXTERNAL_ANDROID_DIR="$EXT_DIR" \
  -DEXTERNAL_INCLUDES_DIR="$EXT_DIR/include" \
  -DCMAKE_SHARED_LINKER_FLAGS="-landroid -llog -lGLESv3 -lGLESv2 -lEGL" \
  -DNCINE_BUILD_TESTS=OFF \
  ${LAUNCHER_ARGS[@]+"${LAUNCHER_ARGS[@]}"}

# ---------------------------------------------------------------- build
START_TS=$(date +%s)
log "Building the libretro core (-j$JOBS)"
"$CMAKE" --build "$CMAKE_BUILD_DIR" -j "$JOBS"
log "build finished in $(( $(date +%s) - START_TS ))s"
command -v ccache >/dev/null 2>&1 && ccache -s | head -6 || true

BUILT_SO="$(find "$CMAKE_BUILD_DIR" -name '*_libretro.so' -type f -print -quit)"
[ -n "$BUILT_SO" ] && [ -f "$BUILT_SO" ] || die "no *_libretro.so produced under $CMAKE_BUILD_DIR"
log "built: $BUILT_SO ($(du -h "$BUILT_SO" | cut -f1) unstripped)"

# ---------------------------------------------------------------- strip + install
log "Stripping"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$BUILT_SO"

# libopenal.so is a NEEDED library of the core, so it has to sit in jniLibs next to it
cp "$EXT_DIR/$ABI/libopenal.so" "$OUT_DIR/libopenal.so"
log "installed $OUT_DIR/libopenal.so"

# ---------------------------------------------------------------- engine content -> cores/jazz2/assets
# Installed by the app as <system dir>/jazz2/Content (core.json assetsInstallDir). This is the engine's own
# data (animation/metadata descriptions and translations), not the original game's files.
log "Copying engine content -> $ASSET_OUT_DIR"
CONTENT="$SRC_DIR/Content"
[ -d "$CONTENT" ] || die "engine content not found at $CONTENT"
rm -rf "$ASSET_OUT_DIR"
mkdir -p "$ASSET_OUT_DIR"
cp -R "$CONTENT/." "$ASSET_OUT_DIR/"
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
    libc.so|libm.so|libdl.so|libz.so|liblog.so|libandroid.so|libGLESv3.so|libGLESv2.so|libEGL.so|libOpenSLES.so|libOpenMAXAL.so|libopenal.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done
log "  Machine : $(grep Machine <<<"$ELF_HEADER" | sed 's/.*: *//')"
log "  Exports : $(grep -cE " T retro_" <<<"$DYN_SYMS") retro_* symbols"
log "  NEEDED  : $(echo $NEEDED)"
log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)), content: $(du -sh "$ASSET_OUT_DIR" | cut -f1)"
