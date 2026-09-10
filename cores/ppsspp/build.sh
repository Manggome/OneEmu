#!/usr/bin/env bash
# OneEmu core build: PPSSPP (Sony PSP) libretro core -> arm64-v8a libppsspp_libretro.so
#
# Usage (from project root):  bash cores/ppsspp/build.sh [--clean]
#
# - Clones https://github.com/hrydgard/ppsspp (recursive) at a pinned commit into cores/ppsspp/src
# - Builds the libretro target with the NDK CMake toolchain (Ninja, Release)
# - Strips and copies to app/src/main/jniLibs/arm64-v8a/libppsspp_libretro.so
# - Copies the runtime asset subset the core needs into cores/ppsspp/assets/
#   (the frontend must install that folder as <libretro system dir>/PPSSPP/)
set -euo pipefail

CORE_ID="ppsspp"
REPO_URL="https://github.com/hrydgard/ppsspp"
PIN_TAG="v1.20.4"
PIN_SHA="fa50bb1976065c4f8b1b47af227d367fe9771555"
NDK_VERSION="28.2.13676358"
ABI="arm64-v8a"
PLATFORM="android-26"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
ASSET_OUT_DIR="$SCRIPT_DIR/assets"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/$ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

log() { printf '\n[%s] %s\n' "$CORE_ID" "$*"; }

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Toolchain discovery (per cores/README.md)
# ---------------------------------------------------------------------------
if [[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
  NDK="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/${NDK_VERSION}" ]]; then
  NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
elif [[ -d "$HOME/Library/Android/sdk/ndk/${NDK_VERSION}" ]]; then
  NDK="$HOME/Library/Android/sdk/ndk/${NDK_VERSION}"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}" ]]; then
  NDK="${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}"
else
  echo "NDK ${NDK_VERSION} not found (set ANDROID_NDK_HOME or ANDROID_HOME)" >&2
  exit 1
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -x "$SDK_ROOT/cmake/3.22.1/bin/cmake" ]]; then
  CMAKE="$SDK_ROOT/cmake/3.22.1/bin/cmake"
  NINJA="$SDK_ROOT/cmake/3.22.1/bin/ninja"
else
  CMAKE="$(command -v cmake)"
  NINJA="$(command -v ninja)"
fi

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
STRIP="$LLVM_BIN/llvm-strip"

if [[ "$(uname -s)" == "Darwin" ]]; then JOBS="$(sysctl -n hw.ncpu)"; else JOBS="$(nproc)"; fi

log "NDK    : $NDK"
log "CMake  : $CMAKE ($("$CMAKE" --version | head -1))"
log "Ninja  : $NINJA"
log "Jobs   : $JOBS"

# ---------------------------------------------------------------------------
# Source checkout (pinned commit, recursive submodules)
# ---------------------------------------------------------------------------
if [[ ! -d "$SRC_DIR/.git" ]]; then
  log "Cloning $REPO_URL @ $PIN_TAG ($PIN_SHA)"
  rm -rf "$SRC_DIR"
  git init -q "$SRC_DIR"
  git -C "$SRC_DIR" remote add origin "$REPO_URL"
  # Shallow fetch of the exact pinned commit (fast; full history not needed)
  git -C "$SRC_DIR" fetch --depth 1 origin "$PIN_SHA"
  git -C "$SRC_DIR" checkout -q FETCH_HEAD
fi

CUR_SHA="$(git -C "$SRC_DIR" rev-parse HEAD)"
if [[ "$CUR_SHA" != "$PIN_SHA" ]]; then
  log "Checking out pinned commit $PIN_SHA (was $CUR_SHA)"
  git -C "$SRC_DIR" fetch --depth 1 origin "$PIN_SHA"
  git -C "$SRC_DIR" checkout -q "$PIN_SHA"
fi

# Tag is only used by git-version.cmake (git describe) to embed a nice version string.
if ! git -C "$SRC_DIR" rev-parse -q --verify "refs/tags/$PIN_TAG" >/dev/null 2>&1; then
  git -C "$SRC_DIR" fetch --depth 1 origin "tag" "$PIN_TAG" >/dev/null 2>&1 || true
fi

log "Updating submodules (recursive, shallow)"
git -C "$SRC_DIR" submodule update --init --recursive --depth 1 -j "$JOBS"

# ---------------------------------------------------------------------------
# Patches (idempotent: skipped when already applied)
# ---------------------------------------------------------------------------
if compgen -G "$PATCH_DIR/*.patch" >/dev/null; then
  for p in "$PATCH_DIR"/*.patch; do
    if git -C "$SRC_DIR" apply --check --reverse "$p" >/dev/null 2>&1; then
      log "Patch already applied: $(basename "$p")"
    elif git -C "$SRC_DIR" apply --check "$p" >/dev/null 2>&1; then
      log "Applying patch: $(basename "$p")"
      git -C "$SRC_DIR" apply "$p"
    else
      echo "Patch does not apply cleanly: $p" >&2
      exit 1
    fi
  done
fi

# ---------------------------------------------------------------------------
# Configure + build
# ---------------------------------------------------------------------------
if [[ "$CLEAN" == "1" ]]; then
  log "Cleaning $BUILD_DIR"
  rm -rf "$BUILD_DIR"
fi
mkdir -p "$BUILD_DIR"

# Flags mirror the libretro buildbot (.gitlab-ci.yml: CORE_ARGS=-DLIBRETRO=ON via the android-cmake
# template, which supplies the NDK toolchain/ABI/platform) plus PPSSPP's own Android defaults:
#   - USE_SYSTEM_FFMPEG=OFF -> uses PPSSPP's prebuilt static ffmpeg in src/ffmpeg/android/arm64
#   - USE_SYSTEM_LIBPNG=OFF -> bundled ext/libpng17 (no libpng in the NDK sysroot)
#   - ANDROID_STL=c++_static -> self-contained .so (no libc++_shared.so dependency)
#   - HEADLESS/UNITTEST/QT off -> only the ppsspp_libretro target
"$CMAKE" -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="$PLATFORM" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DLIBRETRO=ON \
  -DUSE_SYSTEM_FFMPEG=OFF \
  -DUSE_SYSTEM_LIBPNG=OFF \
  -DUSE_SYSTEM_ZSTD=OFF \
  -DUSE_SYSTEM_SNAPPY=OFF \
  -DUSE_SYSTEM_LIBZIP=OFF \
  -DUSE_DISCORD=OFF \
  -DUSING_QT_UI=OFF \
  -DHEADLESS=OFF \
  -DUNITTEST=OFF \
  -DUSE_CCACHE=OFF \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"

log "Building ppsspp_libretro (this takes a while)"
"$CMAKE" --build "$BUILD_DIR" --target ppsspp_libretro -j "$JOBS"

# libretro/CMakeLists.txt sets PREFIX "" and SUFFIX "_android.so" on Android.
BUILT_SO="$BUILD_DIR/ppsspp_libretro_android.so"
if [[ ! -f "$BUILT_SO" ]]; then
  BUILT_SO="$(find "$BUILD_DIR" -maxdepth 2 -name 'ppsspp_libretro*.so' | head -1 || true)"
fi
[[ -f "$BUILT_SO" ]] || { echo "built library not found under $BUILD_DIR" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Strip + install
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
log "Stripping -> $OUT_SO"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$BUILT_SO"

# ---------------------------------------------------------------------------
# Runtime assets (installed by the frontend as <system_dir>/PPSSPP/)
# Only what the libretro core actually reads via its VFS root; UI-only assets
# (font_atlas, ui_images, themes, sfx, ttf, gamecontrollerdb), web debugger
# (debugger/, upload/, mime/) are intentionally skipped. lang/ is kept because libretro.cpp
# calls g_i18nrepo.LoadIni() for the selected PSP language.
# ---------------------------------------------------------------------------
log "Copying runtime assets -> $ASSET_OUT_DIR"
rm -rf "$ASSET_OUT_DIR"
mkdir -p "$ASSET_OUT_DIR"
for f in compat.ini compatvr.ini knownfuncs.ini langregion.ini redump.csv \
         adhoc-servers.json infra-dns.json ppge_atlas.zim ppge_atlas.meta; do
  cp "$SRC_DIR/assets/$f" "$ASSET_OUT_DIR/"
done
for d in flash0 vfpu shaders lang; do
  cp -R "$SRC_DIR/assets/$d" "$ASSET_OUT_DIR/$d"
done
find "$ASSET_OUT_DIR" -name '.DS_Store' -delete 2>/dev/null || true

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
log "Verifying"
"$LLVM_BIN/llvm-readelf" -h "$OUT_SO" | grep -q AArch64 || { echo "not an AArch64 ELF" >&2; exit 1; }
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  "$LLVM_BIN/llvm-nm" -D "$OUT_SO" | grep -qE " T ${sym}$" || { echo "missing exported symbol: $sym" >&2; exit 1; }
done
if "$LLVM_BIN/llvm-readelf" -d "$OUT_SO" | grep -q 'libc++_shared'; then
  echo "unexpected dependency on libc++_shared.so" >&2; exit 1
fi

log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)), assets: $(du -sh "$ASSET_OUT_DIR" | cut -f1)"
"$LLVM_BIN/llvm-readelf" -d "$OUT_SO" | grep NEEDED || true
