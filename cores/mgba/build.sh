#!/usr/bin/env bash
# OneEmu core build: mGBA libretro core (GBA / GB / GBC) for Android arm64-v8a.
#
# Usage (from the project root):
#   bash cores/mgba/build.sh            # clone (pinned) -> configure -> build -> strip -> copy
#   bash cores/mgba/build.sh --clean    # wipe cores/mgba/build first, then rebuild
#
# Output: app/src/main/jniLibs/arm64-v8a/libmgba_libretro.so
# Works on macOS (darwin-x86_64 NDK prebuilt, also used on Apple Silicon) and Ubuntu (linux-x86_64).
set -euo pipefail

CORE_ID="mgba"
SRC_REPO="https://github.com/libretro/mgba"
SRC_COMMIT="e31759b24e7a4e3899285ff720d7b573ac328ae7"   # libretro/mgba master, 2026-08-05, 0.11-10126
NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.22.1"
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-26"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$ANDROID_ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

log() { printf '[%s] %s\n' "$CORE_ID" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$CORE_ID" "$*" >&2; exit 1; }

CLEAN=0
for arg in "$@"; do
	case "$arg" in
		--clean) CLEAN=1 ;;
		*) die "unknown argument: $arg" ;;
	esac
done

# ---------------------------------------------------------------------------
# Toolchain discovery
# ---------------------------------------------------------------------------
if [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
	NDK="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]]; then
	NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
elif [[ -d "$HOME/Library/Android/sdk/ndk/$NDK_VERSION" ]]; then
	NDK="$HOME/Library/Android/sdk/ndk/$NDK_VERSION"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]]; then
	NDK="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
else
	die "Android NDK $NDK_VERSION not found (set ANDROID_NDK_HOME or ANDROID_HOME)"
fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || die "not an NDK: $NDK"

case "$(uname -s)" in
	Darwin) HOST_TAG="darwin-x86_64" ;;
	Linux)  HOST_TAG="linux-x86_64" ;;
	*) die "unsupported host: $(uname -s)" ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$LLVM_BIN/llvm-strip" ]] || die "llvm-strip not found in $LLVM_BIN"

CMAKE_BIN_DIR=""
for cand in "${ANDROID_HOME:-}/cmake/$CMAKE_VERSION/bin" \
            "${ANDROID_SDK_ROOT:-}/cmake/$CMAKE_VERSION/bin" \
            "$HOME/Library/Android/sdk/cmake/$CMAKE_VERSION/bin" \
            "$(dirname "$NDK")/../cmake/$CMAKE_VERSION/bin"; do
	if [[ -n "$cand" && -x "$cand/cmake" ]]; then
		CMAKE_BIN_DIR="$(cd "$cand" && pwd)"
		break
	fi
done
if [[ -n "$CMAKE_BIN_DIR" ]]; then
	CMAKE="$CMAKE_BIN_DIR/cmake"
	NINJA="$CMAKE_BIN_DIR/ninja"
	[[ -x "$NINJA" ]] || NINJA="$(command -v ninja || true)"
else
	CMAKE="$(command -v cmake || true)"
	NINJA="$(command -v ninja || true)"
fi
[[ -n "$CMAKE" && -x "$CMAKE" ]] || die "cmake not found (SDK cmake/$CMAKE_VERSION or PATH)"
[[ -n "$NINJA" && -x "$NINJA" ]] || die "ninja not found (SDK cmake/$CMAKE_VERSION or PATH)"

log "NDK     : $NDK"
log "cmake   : $CMAKE"
log "ninja   : $NINJA"

# ---------------------------------------------------------------------------
# Source: clone at pinned commit (reuse if already present)
# ---------------------------------------------------------------------------
if [[ ! -d "$SRC_DIR/.git" ]]; then
	log "cloning $SRC_REPO"
	rm -rf "$SRC_DIR"
	git clone --recursive "$SRC_REPO" "$SRC_DIR"
fi
CUR_COMMIT="$(git -C "$SRC_DIR" rev-parse HEAD)"
if [[ "$CUR_COMMIT" != "$SRC_COMMIT" ]]; then
	log "checking out $SRC_COMMIT (was $CUR_COMMIT)"
	git -C "$SRC_DIR" cat-file -e "$SRC_COMMIT^{commit}" 2>/dev/null || git -C "$SRC_DIR" fetch --all --tags
	# Drop any previously applied patches before moving to the pinned commit.
	git -C "$SRC_DIR" reset --hard HEAD >/dev/null
	git -C "$SRC_DIR" checkout --detach "$SRC_COMMIT"
	git -C "$SRC_DIR" submodule update --init --recursive
fi

# Patches: always re-apply from a pristine tree so the step is idempotent.
git -C "$SRC_DIR" reset --hard "$SRC_COMMIT" >/dev/null
git -C "$SRC_DIR" clean -fdx >/dev/null
shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
if (( ${#PATCHES[@]} > 0 )); then
	for p in "${PATCHES[@]}"; do
		log "applying patch $(basename "$p")"
		git -C "$SRC_DIR" apply --whitespace=nowarn "$p"
	done
else
	log "no patches to apply"
fi

# ---------------------------------------------------------------------------
# Configure + build (CMake + Ninja, NDK toolchain file)
# ---------------------------------------------------------------------------
if (( CLEAN )); then
	log "cleaning $BUILD_DIR"
	rm -rf "$BUILD_DIR"
fi
mkdir -p "$BUILD_DIR"

# Notes on options:
#  - BUILD_LIBRETRO=ON builds the mgba_libretro shared target (src/platform/libretro).
#    The CMakeLists pins -O3 for the libretro target and hides everything except
#    retro_* via the link.T version script.
#  - Everything frontend/tool/debugger/scripting related is turned off; the
#    libretro target does not use them and some cannot be built for Android.
#  - USE_ZLIB=ON picks up the NDK sysroot libz (public Android system lib) and
#    enables the bundled minizip for .zip ROM loading.
#  - PNG is off (only used for screenshots/savestate thumbnails in mGBA's own UI).
"$CMAKE" -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
	-DCMAKE_MAKE_PROGRAM="$NINJA" \
	-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI="$ANDROID_ABI" \
	-DANDROID_PLATFORM="$ANDROID_PLATFORM" \
	-DANDROID_STL=none \
	-DCMAKE_BUILD_TYPE=Release \
	-DBUILD_LIBRETRO=ON \
	-DBUILD_QT=OFF \
	-DBUILD_SDL=OFF \
	-DBUILD_GL=OFF \
	-DBUILD_GLES2=OFF \
	-DBUILD_GLES3=OFF \
	-DBUILD_TEST=OFF \
	-DBUILD_SUITE=OFF \
	-DBUILD_CINEMA=OFF \
	-DBUILD_PERF=OFF \
	-DBUILD_EXAMPLE=OFF \
	-DBUILD_PYTHON=OFF \
	-DBUILD_UPDATER=OFF \
	-DBUILD_STATIC=OFF \
	-DBUILD_SHARED=OFF \
	-DENABLE_DEBUGGERS=OFF \
	-DENABLE_SCRIPTING=OFF \
	-DUSE_LUA=OFF \
	-DUSE_FFMPEG=OFF \
	-DUSE_DISCORD_RPC=OFF \
	-DUSE_SQLITE3=OFF \
	-DUSE_LIBZIP=OFF \
	-DUSE_MINIZIP=OFF \
	-DUSE_LZMA=OFF \
	-DUSE_ELF=OFF \
	-DUSE_EDITLINE=OFF \
	-DUSE_EPOXY=OFF \
	-DUSE_PNG=OFF \
	-DUSE_ZLIB=ON

log "building mgba_libretro"
"$NINJA" -C "$BUILD_DIR" mgba_libretro

BUILT_SO="$BUILD_DIR/mgba_libretro.so"
[[ -f "$BUILT_SO" ]] || die "build did not produce $BUILT_SO"

# ---------------------------------------------------------------------------
# Strip + install
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
STRIPPED_SO="$BUILD_DIR/lib${CORE_ID}_libretro.so"
"$LLVM_BIN/llvm-strip" --strip-unneeded -o "$STRIPPED_SO" "$BUILT_SO"
cp -f "$STRIPPED_SO" "$OUT_SO"

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
# (capture output instead of `grep -q` in a pipe: with pipefail, grep -q exiting
#  early can SIGPIPE the producer and make a passing check look like a failure)
ELF_HEADER="$("$LLVM_BIN/llvm-readelf" -h "$OUT_SO")"
grep -q AArch64 <<<"$ELF_HEADER" || die "output is not AArch64"
DYN_SYMS="$("$LLVM_BIN/llvm-nm" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
	grep -qE " T ${sym}$" <<<"$DYN_SYMS" || die "missing exported symbol: $sym"
done

log "OK -> $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
