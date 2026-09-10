#!/usr/bin/env bash
# OneEmu core build: FCEUmm (NES / Famicom / FDS) -> arm64-v8a libfceumm_libretro.so
#
# Usage (from project root):  bash cores/fceumm/build.sh [--clean]
# Works on macOS (arm64/x86_64) and Ubuntu (GitHub Actions).
set -euo pipefail

CORE_ID="fceumm"
REPO_URL="https://github.com/libretro/libretro-fceumm"
COMMIT="236ccdfc911e84c60fea6b9d0699c2d440a8de14"   # 2026-08-22, master
NDK_VERSION="28.2.13676358"
API_LEVEL=26
ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$ROOT_DIR/app/src/main/jniLibs/$ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
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
    if [ -n "$c" ] && [ -f "$c/source.properties" ]; then echo "$c"; return 0; fi
  done
  return 1
}
NDK="$(find_ndk)" || { echo "error: NDK $NDK_VERSION not found (set ANDROID_NDK_HOME)" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;   # NDK ships a universal darwin-x86_64 prebuilt (runs on arm64 Macs)
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -d "$TOOLCHAIN" ] || { echo "error: toolchain not found at $TOOLCHAIN" >&2; exit 1; }
BIN="$TOOLCHAIN/bin"
CC="$BIN/${TRIPLE}${API_LEVEL}-clang"
AR="$BIN/llvm-ar"
STRIP="$BIN/llvm-strip"
READELF="$BIN/llvm-readelf"
NM="$BIN/llvm-nm"
[ -x "$CC" ] || { echo "error: compiler not found: $CC" >&2; exit 1; }

# CMake/Ninja are not needed for this core (plain Makefile build), but put the
# SDK CMake dir first on PATH for consistency with other cores.
if [ -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/3.22.1/bin" ]; then
  export PATH="${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/3.22.1/bin:$PATH"
fi

if command -v nproc >/dev/null 2>&1; then JOBS="$(nproc)"; else JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"; fi

echo "== FCEUmm core build"
echo "   NDK:     $NDK ($HOST_TAG)"
echo "   Commit:  $COMMIT"
echo "   Output:  $OUT_SO"

# ---------------------------------------------------------------- source checkout (pinned)
if [ ! -d "$SRC_DIR/.git" ]; then
  echo "== Cloning $REPO_URL"
  rm -rf "$SRC_DIR"
  git clone --recursive "$REPO_URL" "$SRC_DIR"
fi
if [ "$(git -C "$SRC_DIR" rev-parse HEAD)" != "$COMMIT" ]; then
  echo "== Checking out $COMMIT"
  git -C "$SRC_DIR" fetch --quiet origin "$COMMIT" 2>/dev/null || git -C "$SRC_DIR" fetch --quiet origin
  git -C "$SRC_DIR" checkout --quiet --force "$COMMIT"
  git -C "$SRC_DIR" submodule update --init --recursive
  # Tree changed: drop any stale objects.
  make -C "$SRC_DIR" -f Makefile.libretro platform=unix clean >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- patches (idempotent)
shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
for p in ${PATCHES[@]+"${PATCHES[@]}"}; do
  if git -C "$SRC_DIR" apply --check --reverse "$p" >/dev/null 2>&1; then
    echo "== Patch already applied: $(basename "$p")"
  else
    echo "== Applying patch: $(basename "$p")"
    git -C "$SRC_DIR" apply "$p"
  fi
done

# ---------------------------------------------------------------- clean
if [ "$CLEAN" = 1 ]; then
  echo "== Cleaning"
  rm -rf "$BUILD_DIR"
  make -C "$SRC_DIR" -f Makefile.libretro platform=unix clean >/dev/null 2>&1 || true
fi
mkdir -p "$BUILD_DIR" "$OUT_DIR"

# ---------------------------------------------------------------- build
# platform=unix gives: -fPIC, -shared, version script (exports retro_* only),
# -Wl,-no-undefined, WANT_32BPP (XRGB8888 output), HAVE_NTSC=1, HAVE_HDPACK=1,
# -O2 -DNDEBUG (DEBUG=0). We only swap in the NDK toolchain and add Android
# specific link flags (16 KB page alignment for Android 15+, -lm/-llog).
# Note: LDFLAGS on the make command line overrides the Makefile's "LDFLAGS += -lm",
# so -lm is repeated here on purpose.
COMMON_FLAGS="-O2 -DNDEBUG -fPIC -ffunction-sections -fdata-sections -fno-strict-aliasing -D__ANDROID__ -DANDROID -D_FILE_OFFSET_BITS=64"
echo "== Building (make -j$JOBS)"
make -C "$SRC_DIR" -f Makefile.libretro -j"$JOBS" \
  platform=unix \
  CC="$CC $COMMON_FLAGS" \
  LD="$CC" \
  AR="$AR" \
  LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--build-id=sha1 -lm -llog" \
  GIT_VERSION=" $(git -C "$SRC_DIR" rev-parse --short HEAD)"

UNSTRIPPED="$BUILD_DIR/lib${CORE_ID}_libretro.unstripped.so"
cp -f "$SRC_DIR/fceumm_libretro.so" "$UNSTRIPPED"
echo "== Stripping"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$UNSTRIPPED"

# ---------------------------------------------------------------- verify
# (capture output first: with pipefail, `readelf | grep -q` can fail on SIGPIPE)
echo "== Verifying"
ELF_HEADER="$("$READELF" -h "$OUT_SO")"
grep -q "AArch64" <<<"$ELF_HEADER" || { echo "error: not an AArch64 ELF" >&2; exit 1; }
DYN_SYMS="$("$NM" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  grep -qE " T ${sym}$" <<<"$DYN_SYMS" || { echo "error: missing exported symbol $sym" >&2; exit 1; }
done
# Every LOAD segment must be 16 KB aligned (Android 15 requirement).
LOAD_ALIGNS="$("$READELF" -l "$OUT_SO" | awk '/^  LOAD/ {print $NF}')"
if grep -qvE '^0x(4000|10000)$' <<<"$LOAD_ALIGNS"; then
  echo "error: LOAD segments are not 16 KB aligned: $LOAD_ALIGNS" >&2; exit 1
fi
echo "   Machine : $(grep Machine <<<"$ELF_HEADER" | sed 's/.*: *//')"
echo "   Exports : $(grep -cE " T retro_" <<<"$DYN_SYMS") retro_* symbols"
echo "== OK: $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
