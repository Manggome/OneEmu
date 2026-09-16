#!/usr/bin/env bash
# OneEmu core build: PCSX-ReARMed (PlayStation 1) -> Android arm64-v8a libpcsx_rearmed_libretro.so
#
# Usage (from project root):  bash cores/pcsx_rearmed/build.sh [--clean]
# Works on macOS (arm64/x86_64) and Ubuntu (GitHub Actions).
#
# Build path: the repo's jni/Android.mk with ndk-build, which is exactly what the libretro
# buildbot uses for Android. Makefile.libretro has no android platform target. For
# TARGET_ARCH_ABI=arm64-v8a Android.mk selects:
#   - HAVE_ARI64=1     -> Ari64 ARM64 dynarec (new_dynarec.c + linkage_arm64.S, NDRC_THREAD=1)
#   - HAVE_GPU_NEON=1  -> NEON GPU (psx_gpu, SIMD_BUILD C-intrinsics port on aarch64)
#   - libchdr with the ARM64 LZMA asm decoder, libretro VFS, async CD/GPU/SPU threads
# The script asserts both the dynarec and the NEON GPU actually ended up in the binary.
set -euo pipefail

CORE_ID="pcsx_rearmed"
REPO_URL="https://github.com/libretro/pcsx_rearmed"
REPO_SHA="8625c395a24411f8c77e69802b516df9c613a712"   # 2026-09-05 master "Fix libretro seek status compatibility in core adapters"
NDK_VERSION="28.2.13676358"
ANDROID_API="26"
ABI="arm64-v8a"
if command -v nproc >/dev/null 2>&1; then JOBS="${JOBS:-$(nproc)}"; else JOBS="${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || echo 4)}"; fi

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
  Darwin) HOST_TAG="darwin-x86_64" ;;   # universal prebuilt, also runs on Apple Silicon
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) die "unsupported host: $(uname -s)" ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$LLVM_BIN/clang" ] || die "NDK clang not found at $LLVM_BIN/clang"

# CMake/Ninja are not needed (ndk-build), but keep the project-wide convention of
# preferring the SDK copies when present.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/cmake/3.22.1/bin" ]; then
  export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
elif [ -d "$HOME/Library/Android/sdk/cmake/3.22.1/bin" ]; then
  export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
fi

log "NDK:      $NDK ($HOST_TAG)"
log "commit:   $REPO_SHA"
log "source:   $SRC_DIR"
log "output:   $OUT_SO"

# ---------------------------------------------------------------- source (pinned, recursive: deps/libchdr etc.)
if [ ! -d "$SRC_DIR/.git" ]; then
  log "cloning $REPO_URL"
  rm -rf "$SRC_DIR"
  git clone --recursive "$REPO_URL" "$SRC_DIR"
fi
(
  cd "$SRC_DIR"
  if [ "$(git rev-parse HEAD)" != "$REPO_SHA" ]; then
    log "checking out $REPO_SHA"
    git fetch --quiet origin "$REPO_SHA" 2>/dev/null || git fetch --quiet origin
    git checkout --quiet --force "$REPO_SHA"
    # Tree changed: stale objects would otherwise be reused by ndk-build's timestamp check.
    rm -rf "$BUILD_DIR/obj"
  fi
  git submodule update --init --recursive --quiet
)

# ---------------------------------------------------------------- patches (idempotent)
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
else
  log "no patches"
fi

# ---------------------------------------------------------------- build
if [ "$CLEAN" = 1 ]; then
  log "--clean: removing $BUILD_DIR"
  rm -rf "$BUILD_DIR"
fi
mkdir -p "$BUILD_DIR"

# Application.mk says APP_ABI := all; command-line variables override it. Android.mk
# shells out to `git describe` in its own directory to write include/revision.h, so the
# checkout must stay a git repo. V=1 so the compile lines can be checked for the dynarec
# and NEON GPU defines afterwards.
BUILD_LOG="$BUILD_DIR/ndk-build.log"
START_TS=$(date +%s)
log "building with ndk-build (-j$JOBS, $ABI, android-$ANDROID_API, release) -> $BUILD_LOG"
(
  cd "$SRC_DIR"
  "$NDK/ndk-build" \
    -j"$JOBS" V=1 \
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
) > "$BUILD_LOG" 2>&1 || { tail -n 60 "$BUILD_LOG" >&2; die "ndk-build failed (full log: $BUILD_LOG)"; }
END_TS=$(date +%s)
log "build finished in $((END_TS - START_TS))s"

BUILT_SO="$BUILD_DIR/libs/$ABI/libretro.so"
[ -f "$BUILT_SO" ] || die "expected output not found: $BUILT_SO"

# ---------------------------------------------------------------- strip + install
mkdir -p "$OUT_DIR"
cp -f "$BUILT_SO" "$OUT_SO"
"$LLVM_BIN/llvm-strip" --strip-unneeded "$OUT_SO"

# ---------------------------------------------------------------- verify
# (capture output first: with pipefail, `readelf | grep -q` can fail on SIGPIPE)
ELF_HEADER="$("$LLVM_BIN/llvm-readelf" -h "$OUT_SO")"
grep -q AArch64 <<<"$ELF_HEADER" || die "output is not AArch64"
DYN_SYMS="$("$LLVM_BIN/llvm-nm" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  grep -qE " T ${sym}$" <<<"$DYN_SYMS" || die "missing exported symbol: $sym"
done
# Every LOAD segment must be 16 KB aligned (Android 15 requirement).
LOAD_ALIGNS="$("$LLVM_BIN/llvm-readelf" -l "$OUT_SO" | awk '/^  LOAD/ {print $NF}')"
if grep -qvE '^0x(4000|10000)$' <<<"$LOAD_ALIGNS"; then
  die "LOAD segments are not 16 KB aligned: $LOAD_ALIGNS"
fi
# The ARM64 dynarec and the NEON GPU are the point of this core on Android: make sure
# Android.mk really compiled them in (objects present + defines on the compile lines).
OBJ_DIR="$BUILD_DIR/obj/local/$ABI/objs/retro"
[ -n "$(find "$OBJ_DIR" -name 'new_dynarec.o' -print -quit)" ] || die "Ari64 dynarec (new_dynarec.o) was not built"
[ -n "$(find "$OBJ_DIR" -name 'linkage_arm64.o' -print -quit)" ] || die "Ari64 dynarec (linkage_arm64.o) was not built"
[ -n "$(find "$OBJ_DIR" -name 'psx_gpu_if.o' -print -quit)" ] || die "NEON GPU (psx_gpu_if.o) was not built"
[ -n "$(find "$OBJ_DIR" -name 'psx_gpu_simd.o' -print -quit)" ] || die "NEON GPU (psx_gpu_simd.o) was not built"
# (compile lines are only present when something was actually rebuilt; an up-to-date
#  second run logs just "Nothing to be done", so the object checks above are the constant.)
if grep -q -- ' -c ' "$BUILD_LOG"; then
  grep -q -- '-DGPU_NEON' "$BUILD_LOG" || die "-DGPU_NEON missing from compile flags"
  grep -q -- '-DNDRC_THREAD' "$BUILD_LOG" || die "-DNDRC_THREAD missing from compile flags"
  grep -q -- '-DHAVE_CHD' "$BUILD_LOG" || die "-DHAVE_CHD missing from compile flags"
  if grep -q -- '-DDRC_DISABLE' "$BUILD_LOG"; then die "dynarec was disabled (-DDRC_DISABLE)"; fi
fi

log "machine : $(grep Machine <<<"$ELF_HEADER" | sed 's/.*: *//')"
log "exports : $(grep -cE " T retro_" <<<"$DYN_SYMS") retro_* symbols"
log "OK -> $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
