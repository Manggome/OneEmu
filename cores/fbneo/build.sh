#!/usr/bin/env bash
# OneEmu core build: FinalBurn Neo (libretro/FBNeo) -> Android arm64-v8a
# Usage (from project root):  bash cores/fbneo/build.sh [--clean]
#
# A "distribution": "download" core (cores/README.md): CI (.github/workflows/cores.yml) builds it and publishes
# it on the rolling `cores` release; the APK carries only core.json + assets/ (romdb.tsv.gz, titles.tsv).
set -euo pipefail

CORE_ID="fbneo"
REPO_URL="https://github.com/libretro/FBNeo"
REPO_SHA="d15071abf14191eebb4a5d7c7147398edcf237c8"   # 2026-11-06 "Merge branch 'finalburnneo-master'" (DAT v1.0.0.03)
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
elif [ -n "${LOCALAPPDATA:-}" ] && [ -d "$LOCALAPPDATA/Android/Sdk/ndk/$NDK_VERSION" ]; then
  NDK="$LOCALAPPDATA/Android/Sdk/ndk/$NDK_VERSION"
else
  die "Android NDK $NDK_VERSION not found (set ANDROID_NDK_HOME or ANDROID_HOME)"
fi

# Git Bash on Windows works too (home PC): the NDK there is windows-x86_64, ndk-build is a .cmd script and
# the tools want native paths (run from an ASCII-only path, e.g. a `subst` drive, since ndk-build/make choke
# on spaces and non-ASCII characters).
EXE=""
NDK_BUILD="ndk-build"
to_native() { printf '%s' "$1"; }
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*)
    HOST_TAG="windows-x86_64"; EXE=".exe"; NDK_BUILD="ndk-build.cmd"
    to_native() { cygpath -m "$1"; }
    ;;
  *) die "unsupported host: $(uname -s)" ;;
esac
[ -f "$NDK/$NDK_BUILD" ] || die "$NDK_BUILD not found in $NDK"
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$LLVM_BIN/clang$EXE" ] || die "NDK clang not found at $LLVM_BIN/clang$EXE"

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
# The FBNeo history is large (years of ROM-set churn): clone shallow, then fetch the pinned commit shallowly.
# core.autocrlf=false: the checked-in generated headers and the DATs must keep their upstream bytes.
if [ ! -d "$SRC_DIR/.git" ]; then
  log "cloning $REPO_URL (shallow)"
  rm -rf "$SRC_DIR"
  git -c core.autocrlf=false clone --depth 1 "$REPO_URL" "$SRC_DIR"
fi
(
  cd "$SRC_DIR"
  git config core.autocrlf false
  if [ "$(git rev-parse HEAD)" != "$REPO_SHA" ]; then
    log "checking out $REPO_SHA"
    git fetch --quiet --depth 1 origin "$REPO_SHA" 2>/dev/null || git fetch --quiet --unshallow origin
    git checkout --quiet --force "$REPO_SHA"
  fi
  # no submodules (zlib, 7z, libchdr are vendored under src/dep)
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

# The repo ships src/burner/libretro/jni/Android.mk (the path libretro's buildbot uses for Android): it pulls
# the object list from ../Makefile.common + ../Makefile.all (every driver, Neo Geo/CPS1-2-3/PGM/… included)
# and compiles with the NDK clang only. The generated driver list and sprite/tile renderers are checked in
# under src/dep/generated, so no perl/host tools run at build time. Android.mk already links with
# -z max-page-size=16384 (16 KB pages) and a version script that exports only retro_*; Application.mk asks
# for c++_static, so no libc++_shared.so is needed at runtime. arm64 uses the C 68000 core (Musashi), not
# Cyclone (32-bit ARM only). APP_OPTIM=release gives -O2 -DNDEBUG; command-line variables override
# Application.mk's APP_ABI := <all> and APP_PLATFORM := android-18.
JNI_DIR="$SRC_DIR/src/burner/libretro/jni"
START_TS=$(date +%s)
log "building with ndk-build (-j$JOBS, $ABI, android-$ANDROID_API, release)"
(
  cd "$SRC_DIR"   # Makefile.common shells out to `git rev-parse` in the cwd for the version string
  "$NDK/$NDK_BUILD" \
    -j"$JOBS" \
    NDK_PROJECT_PATH="$(to_native "$SRC_DIR/src/burner/libretro")" \
    APP_BUILD_SCRIPT="$(to_native "$JNI_DIR/Android.mk")" \
    NDK_APPLICATION_MK="$(to_native "$JNI_DIR/Application.mk")" \
    NDK_OUT="$(to_native "$BUILD_DIR/obj")" \
    NDK_LIBS_OUT="$(to_native "$BUILD_DIR/libs")" \
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
"$LLVM_BIN/llvm-strip$EXE" --strip-unneeded "$OUT_SO"

# ---------------------------------------------------------------- verify
"$LLVM_BIN/llvm-readelf$EXE" -h "$OUT_SO" | grep -c AArch64 >/dev/null || die "output is not AArch64"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  "$LLVM_BIN/llvm-nm$EXE" -D "$OUT_SO" | grep -cE " T ${sym}$" >/dev/null || die "missing exported symbol: $sym"
done
# 16 KB page size: every PT_LOAD segment must be aligned to 0x4000.
BAD_ALIGN=$("$LLVM_BIN/llvm-readelf$EXE" -lW "$OUT_SO" | tr -d '\r' | awk '$1=="LOAD" && $NF!="0x4000" && $NF!="0x10000"' | wc -l | tr -d ' ')
[ "$BAD_ALIGN" = 0 ] || die "a PT_LOAD segment is not 16 KB aligned"
# Only bionic libraries may be NEEDED (no libc++_shared.so, no host libs).
NEEDED=$("$LLVM_BIN/llvm-readelf$EXE" -d "$OUT_SO" | tr -d '\r' | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}')
for lib in $NEEDED; do
  case "$lib" in
    libc.so|libm.so|libdl.so|libz.so|liblog.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done

log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)) NEEDED: $(echo $NEEDED)"
