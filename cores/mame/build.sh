#!/usr/bin/env bash
# OneEmu core build: MAME (current libretro/mame, upstream MAME 0.289, arcade subtarget) -> Android arm64-v8a
# Usage (from project root):  bash cores/mame/build.sh [--clean]
#
# Downloadable core (core.json "distribution": "download"): the .so is still written under jniLibs for
# uniformity; the cores CI packages it into the GitHub release instead of the APK.
#
# This is a very large build: ~3-4 h and ~10 GB of objects on an 8-core Mac from scratch. ccache is used
# automatically when present (brew install ccache / apt install ccache) so re-runs take minutes.
set -euo pipefail

CORE_ID="mame"
REPO_URL="https://github.com/libretro/mame"
REPO_SHA="4fc9a9312baaf34963847f884961ad9793fbbc1d"   # 2026-09-04 master "Cleanup parse_cmdline" (MAME 0.289)
NDK_VERSION="28.2.13676358"
ANDROID_API="26"
ABI="arm64-v8a"
JOBS="${JOBS:-8}"
# libretro's buildbot (.gitlab-ci.yml, android-arm64-v8a job) builds TARGET=mame SUBTARGET=arcade
# (CORENAME mamearcade): arcade drivers only, no MESS computers/consoles. Same here; the output is
# renamed to lib<id>_libretro.so. Core option keys stay "mame_*" (CORE_NAME is "mame" for every subtarget).
MAME_TARGET="mame"
MAME_SUBTARGET="arcade"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"          # ccache dir + build log; MAME itself builds in-tree (src/build)
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

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) die "unsupported host: $(uname -s)" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
LLVM_BIN="$TOOLCHAIN/bin"
[ -x "$LLVM_BIN/clang" ] || die "NDK clang not found at $LLVM_BIN/clang"

# CMake/Ninja are not needed for this core (GENie + make), but keep the
# project-wide convention of preferring the SDK copies when present.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/cmake/3.22.1/bin" ]; then
  export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
elif [ -d "$HOME/Library/Android/sdk/cmake/3.22.1/bin" ]; then
  export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
fi

command -v python3 >/dev/null || die "python3 is required (MAME generates sources with it)"
command -v make >/dev/null || die "GNU make is required"

log "NDK:      $NDK"
log "source:   $SRC_DIR"
log "output:   $OUT_SO"

# ---------------------------------------------------------------- source
# The MAME repository history is several GB: clone shallow, then fetch the pinned commit shallowly.
if [ ! -d "$SRC_DIR/.git" ]; then
  log "cloning $REPO_URL (shallow)"
  rm -rf "$SRC_DIR"
  git clone --depth 1 "$REPO_URL" "$SRC_DIR"
fi
(
  cd "$SRC_DIR"
  if [ "$(git rev-parse HEAD)" != "$REPO_SHA" ]; then
    log "checking out $REPO_SHA"
    git fetch --quiet --depth 1 origin "$REPO_SHA" 2>/dev/null || git fetch --quiet --unshallow origin
    git checkout --quiet --force "$REPO_SHA"
  fi
  # no submodules (3rdparty is vendored)
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
  log "--clean: removing $BUILD_DIR and $SRC_DIR/build"
  rm -rf "$BUILD_DIR" "$SRC_DIR/build"
fi
mkdir -p "$BUILD_DIR"

# ccache (optional): patch 0001 makes GENie prefix the NDK compilers with $ANDROID_CC_LAUNCHER.
if command -v ccache >/dev/null 2>&1; then
  export ANDROID_CC_LAUNCHER="ccache"
  export CCACHE_DIR="${CCACHE_DIR:-$BUILD_DIR/ccache}"
  export CCACHE_BASEDIR="$SRC_DIR"
  export CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-20G}"
  export CCACHE_SLOPPINESS="time_macros,include_file_mtime,include_file_ctime"
  log "ccache: $(command -v ccache) (dir $CCACHE_DIR)"
else
  unset ANDROID_CC_LAUNCHER
  log "ccache not found: full rebuilds every time (brew install ccache / apt install ccache)"
fi

# What libretro's buildbot does (.gitlab-ci.yml android-arm64-v8a job -> android-make.yml template ->
# Makefile.libretro platform=android-arm64 TARGET=mame SUBTARGET=arcade). Makefile.libretro only composes
# flags for the upstream makefile; its android branch is
#   REGENIE=1 VERBOSE=1 NOWERROR=1 OSD=retro NO_USE_MIDI=1 NO_USE_PORTAUDIO=1 CONFIG=libretro
#   TARGETOS=android-arm64 gcc=android-arm64 PLATFORM=arm64
# but the upstream default goal ($(TARGETOS)$(ARCHITECTURE)) has no rule for android, so the explicit
# "android-arm64" goal is required (makefile rule "android-arm64": GENie --gcc=android-arm64 --osd=retro
# --targetos=android --PLATFORM=arm64 --NO_USE_MIDI=1 --NO_OPENGL=1 --USE_QTDEBUG=0 --DONT_USE_NETWORK=1
# --NOASM=1, then make precompile + make). We therefore call the upstream makefile directly with the same
# flags plus that goal. It compiles with the NDK clang (--target=aarch64-none-linux-android$ANDROID_API),
# links libc++ statically (-static-libstdc++, toolchain.lua) and, with PLATFORM=arm64 and no
# FORCE_DRC_C_BACKEND, enables the native AArch64 UML DRC backend (scripts/src/cpu.lua).
# Release: CONFIG=libretro is MAME's optimised config (OPTIMIZE=3 default, no symbols, NDEBUG).
# ANDROID_API goes through patch 0001 (--with-android; upstream default is 24).
# LDOPTS: --as-needed drops the unused -landroid; max-page-size keeps 16 KB page compatibility explicit
# (NDK r28 already defaults to it).
START_TS=$(date +%s)
log "building $MAME_TARGET/$MAME_SUBTARGET with GENie+make (-j$JOBS, $ABI, android-$ANDROID_API, release)"
(
  cd "$SRC_DIR"
  make \
    -j"$JOBS" \
    REGENIE=1 VERBOSE=1 NOWERROR=1 \
    OSD=retro NO_USE_MIDI=1 NO_USE_PORTAUDIO=1 \
    CONFIG=libretro \
    TARGETOS=android gcc=android-arm64 PLATFORM=arm64 \
    ANDROID_NDK_HOME="$NDK" \
    ANDROID_API="$ANDROID_API" \
    TARGET="$MAME_TARGET" \
    SUBTARGET="$MAME_SUBTARGET" \
    LDOPTS="-Wl,--as-needed -Wl,-z,max-page-size=16384" \
    PYTHON_EXECUTABLE="$(command -v python3)" \
    android-arm64 \
    2>&1 | tee "$BUILD_DIR/build.log" | grep --line-buffered -v '^Compiling \|^Archiving \|^Precompiling '
  exit "${PIPESTATUS[0]}"
)
END_TS=$(date +%s)
log "build finished in $((END_TS - START_TS))s"
command -v ccache >/dev/null 2>&1 && ccache -s | head -6 || true

# GENie writes the libretro target into the source root (TARGETDIR = MAME_DIR) and, lacking a
# targetextension for android in scripts/src/main.lua, uses the *host's* shared-library suffix:
# .so on Linux, .dylib on macOS. Either way it is an AArch64 ELF (verified below).
BUILT_SO=""
for cand in "$SRC_DIR/${MAME_TARGET}${MAME_SUBTARGET}_libretro_android.so" \
            "$SRC_DIR/${MAME_TARGET}${MAME_SUBTARGET}_libretro_android.dylib"; do
  [ -f "$cand" ] && { BUILT_SO="$cand"; break; }
done
[ -n "$BUILT_SO" ] || BUILT_SO="$(find "$SRC_DIR/build/libretro" -name '*_libretro_android.*' -type f -print -quit 2>/dev/null || true)"
[ -n "$BUILT_SO" ] && [ -f "$BUILT_SO" ] || die "expected output ${MAME_TARGET}${MAME_SUBTARGET}_libretro_android.{so,dylib} not found in $SRC_DIR"
log "built: $BUILT_SO ($(du -h "$BUILT_SO" | cut -f1) unstripped)"

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
    libc.so|libm.so|libdl.so|libz.so|liblog.so|libandroid.so) ;;
    *) die "unexpected NEEDED library: $lib" ;;
  esac
done

log "OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1)) NEEDED: $(echo $NEEDED)"
