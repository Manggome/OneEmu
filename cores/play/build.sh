#!/usr/bin/env bash
# OneEmu core build: Play! (PS2 HLE emulator) libretro core -> arm64-v8a
# Usage: bash cores/play/build.sh [--clean]
set -euo pipefail

CORE_ID="play"
REPO_URL="https://github.com/jpd002/Play-"
# Pinned commit (master, 2026-09-03). Bump deliberately after re-verifying the build.
COMMIT="83700b2c31e593bc94e845b4b31b797be84dda59"
NDK_VERSION="28.2.13676358"
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-26"

# ---- paths ------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/$ANDROID_ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ---- toolchain discovery ----------------------------------------------------
find_ndk() {
  local c
  for c in "${ANDROID_NDK_HOME:-}" \
           "${ANDROID_HOME:-}/ndk/$NDK_VERSION" \
           "$HOME/Library/Android/sdk/ndk/$NDK_VERSION" \
           "$HOME/Android/Sdk/ndk/$NDK_VERSION"; do
    if [ -n "$c" ] && [ -f "$c/build/cmake/android.toolchain.cmake" ]; then
      echo "$c"; return 0
    fi
  done
  return 1
}
NDK="$(find_ndk)" || { echo "ERROR: NDK $NDK_VERSION not found (set ANDROID_NDK_HOME)" >&2; exit 1; }

CMAKE_BIN_DIR=""
for c in "${ANDROID_HOME:-}/cmake/3.22.1/bin" \
         "$HOME/Library/Android/sdk/cmake/3.22.1/bin" \
         "$HOME/Android/Sdk/cmake/3.22.1/bin"; do
  if [ -x "$c/cmake" ] && [ -x "$c/ninja" ]; then CMAKE_BIN_DIR="$c"; break; fi
done
if [ -n "$CMAKE_BIN_DIR" ]; then
  CMAKE="$CMAKE_BIN_DIR/cmake"; NINJA="$CMAKE_BIN_DIR/ninja"
else
  CMAKE="$(command -v cmake)" || { echo "ERROR: cmake not found" >&2; exit 1; }
  NINJA="$(command -v ninja)" || { echo "ERROR: ninja not found" >&2; exit 1; }
fi

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "ERROR: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
STRIP="$LLVM_BIN/llvm-strip"

if command -v nproc >/dev/null 2>&1; then JOBS="$(nproc)"; else JOBS="$(sysctl -n hw.ncpu)"; fi

echo "== Play! libretro core build"
echo "   NDK:    $NDK"
echo "   CMake:  $CMAKE"
echo "   Ninja:  $NINJA"
echo "   Commit: $COMMIT"

# ---- source -----------------------------------------------------------------
if [ ! -d "$SRC_DIR/.git" ]; then
  echo "== Cloning $REPO_URL"
  git clone --recursive "$REPO_URL" "$SRC_DIR"
fi
pushd "$SRC_DIR" >/dev/null
if [ "$(git rev-parse HEAD)" != "$COMMIT" ]; then
  echo "== Checking out $COMMIT"
  git fetch --all --tags
  git checkout --detach "$COMMIT"
  git submodule update --init --recursive
else
  # Make sure submodules are present even when src/ was pre-populated at the right commit.
  git submodule update --init --recursive
fi

# ---- patches (idempotent: skip patches already applied) ----------------------
if [ -d "$PATCH_DIR" ] && ls "$PATCH_DIR"/*.patch >/dev/null 2>&1; then
  for p in "$PATCH_DIR"/*.patch; do
    if git apply --check "$p" >/dev/null 2>&1; then
      echo "== Applying patch $(basename "$p")"
      git apply "$p"
    elif git apply --check --reverse "$p" >/dev/null 2>&1; then
      echo "== Patch $(basename "$p") already applied"
    else
      echo "ERROR: patch $(basename "$p") does not apply" >&2; exit 1
    fi
  done
fi
popd >/dev/null

# ---- configure + build ------------------------------------------------------
if [ "$CLEAN" = "1" ]; then rm -rf "$BUILD_DIR"; fi
mkdir -p "$BUILD_DIR"

"$CMAKE" -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="$NDK" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
  -DANDROID_STL=c++_static \
  -DANDROID_TOOLCHAIN=clang \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_LIBRETRO_CORE=ON \
  -DBUILD_PLAY=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_PSFPLAYER=OFF \
  -DENABLE_AMAZON_S3=OFF

"$CMAKE" --build "$BUILD_DIR" --target play_libretro -j "$JOBS"

# ---- strip + install --------------------------------------------------------
BUILT_SO="$BUILD_DIR/Source/ui_libretro/play_libretro_android.so"
[ -f "$BUILT_SO" ] || { echo "ERROR: $BUILT_SO not produced" >&2; exit 1; }
mkdir -p "$OUT_DIR"
"$STRIP" --strip-all -o "$OUT_SO" "$BUILT_SO"

# ---- verify -----------------------------------------------------------------
"$LLVM_BIN/llvm-readelf" -h "$OUT_SO" | grep -q AArch64 || { echo "ERROR: not AArch64" >&2; exit 1; }
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  "$LLVM_BIN/llvm-nm" -D "$OUT_SO" | grep -qE " T ${sym}$" || { echo "ERROR: missing export $sym" >&2; exit 1; }
done
echo "== OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
