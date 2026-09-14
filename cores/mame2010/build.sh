#!/usr/bin/env bash
# OneEmu core build: MAME 2010 (libretro, MAME 0.139) -> Android arm64-v8a
# Usage (from project root):  bash cores/mame2010/build.sh [--clean]
set -euo pipefail

CORE_ID="mame2010"
REPO_URL="https://github.com/libretro/mame2010-libretro"
REPO_SHA="dff8aadd1c3f38215af3955746d6e19abe0ddcea"   # 2026-09-02 "Fix the x86 inline asm clobber, and build the Android x86 ABI (#172)"
NDK_VERSION="28.2.13676358"
ANDROID_API="26"
ABI="arm64-v8a"
JOBS="${JOBS:-8}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/$ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

log() { printf '\033[1;34m[%s]\033[0m %s\n' "$CORE_ID" "$*"; }
die() { printf '\033[1;31m[%s] ERROR:\033[0m %s\n' "$CORE_ID" "$*" >&2; exit 1; }

# ---------------------------------------------------------------- args
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

# ---------------------------------------------------------------- NDK lookup
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
  NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
  NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
elif [ -d "$HOME/Library/Android/sdk/ndk/$NDK_VERSION" ]; then
  NDK="$HOME/Library/Android/sdk/ndk/$NDK_VERSION"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
  NDK="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
else
  die "Android NDK $NDK_VERSION not found (set ANDROID_NDK_HOME or ANDROID_HOME)"
fi
[ -x "$NDK/ndk-build" ] || die "ndk-build not found in $NDK"

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) die "unsupported host: $(uname -s)" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
LLVM_BIN="$TOOLCHAIN/bin"
[ -x "$LLVM_BIN/clang" ] || die "NDK clang not found at $LLVM_BIN/clang"

# CMake/Ninja are not needed for this core (ndk-build), but keep the
# project-wide convention of preferring the SDK copies when present.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/cmake/3.22.1/bin" ]; then
  export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
elif [ -d "$HOME/Library/Android/sdk/cmake/3.22.1/bin" ]; then
  export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
fi

log "NDK:      $NDK"
log "source:   $SRC_DIR"
log "output:   $OUT_SO"

# ---------------------------------------------------------------- source
if [ ! -d "$SRC_DIR/.git" ]; then
  log "cloning $REPO_URL"
  rm -rf "$SRC_DIR"
  git clone "$REPO_URL" "$SRC_DIR"
fi
(
  cd "$SRC_DIR"
  if [ "$(git rev-parse HEAD)" != "$REPO_SHA" ]; then
    log "checking out $REPO_SHA"
    git fetch --quiet origin "$REPO_SHA" 2>/dev/null || git fetch --quiet origin
    git checkout --quiet --force "$REPO_SHA"
  fi
  git submodule update --init --recursive --quiet 2>/dev/null || true
)

# Apply patches idempotently: skip a patch if it is already applied.
if compgen -G "$PATCH_DIR/*.patch" > /dev/null; then
  for p in "$PATCH_DIR"/*.patch; do
    if git -C "$SRC_DIR" apply --check --reverse "$p" >/dev/null 2>&1; then
      log "patch already applied: $(basename "$p")"
    elif git -C "$SRC_DIR" apply --check "$p" >/dev/null 2>&1; then
      log "applying patch: $(basename "$p")"
      git -C "$SRC_DIR" apply "$p"
    else
      die "patch does not apply cleanly: $p"
    fi
  done
fi

# ---------------------------------------------------------------- build
if [ "$CLEAN" = 1 ]; then
  log "--clean: removing $BUILD_DIR"
  rm -rf "$BUILD_DIR"
fi
mkdir -p "$BUILD_DIR"

# The repo ships jni/Android.mk (the path libretro's buildbot uses for Android):
# it includes Makefile.common for the object list and compiles every source
# with the NDK clang, so no host compiler is involved. MAME 0.139 needs no
# generated sources at build time either: the layout headers (src/**/*.lh) and
# precompile/{hiscore_dat,mameini_boilerplate}.h are checked in.
# APP_OPTIM=release gives -O2 -DNDEBUG (the upstream buildbot flags).
# Application.mk says APP_ABI := <all>; command-line variables override it.
START_TS=$(date +%s)
log "building with ndk-build (-j$JOBS, $ABI, android-$ANDROID_API, release)"
(
  cd "$SRC_DIR"   # Android.mk shells out to `git rev-parse` in the cwd
  "$NDK/ndk-build" \
    -j"$JOBS" \
    NDK_PROJECT_PATH="$SRC_DIR" \
    APP_BUILD_SCRIPT="$SRC_DIR/jni/Android.mk" \
    NDK_APPLICATION_MK="$SRC_DIR/jni/Application.mk" \
    NDK_OUT="$BUILD_DIR/obj" \
    NDK_LIBS_OUT="$BUILD_DIR/libs" \
    APP_ABI="$ABI" \
    APP_PLATFORM="android-$ANDROID_API" \
    APP_OPTIM=release \
    NDK_DEBUG=0 \
    APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true
)
END_TS=$(date +%s)
log "build finished in $((END_TS - START_TS))s"

BUILT_SO="$BUILD_DIR/libs/$ABI/libretro.so"
[ -f "$BUILT_SO" ] || die "expected output not found: $BUILT_SO"

# ---------------------------------------------------------------- strip + install
mkdir -p "$OUT_DIR"
cp -f "$BUILT_SO" "$OUT_SO"
"$LLVM_BIN/llvm-strip" --strip-unneeded "$OUT_SO"

# ---------------------------------------------------------------- verify
"$LLVM_BIN/llvm-readelf" -h "$OUT_SO" | grep -c AArch64 >/dev/null || die "output is not AArch64"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  "$LLVM_BIN/llvm-nm" -D "$OUT_SO" | grep -cE " T ${sym}$" >/dev/null || die "missing exported symbol: $sym"
done
# 16 KB page size: every PT_LOAD segment must be aligned to 0x4000.
BAD_ALIGN=$("$LLVM_BIN/llvm-readelf" -lW "$OUT_SO" | awk '$1=="LOAD" && $NF!="0x4000" && $NF!="0x10000"' | wc -l | tr -d ' ')
[ "$BAD_ALIGN" = 0 ] || die "a PT_LOAD segment is not 16 KB aligned"
# Only bionic libraries may be NEEDED (no libc++_shared.so, no host libs).
NEEDED=$("$LLVM_BIN/llvm-readelf" -d "$OUT_SO" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}')
for lib in $NEEDED; do
  case "$lib" in
    libc.so|libm.so|libdl.so|libz.so|liblog.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done

log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)) NEEDED: $(echo $NEEDED)"
