#!/usr/bin/env bash
# OneEmu core build: WIPI feature-phone core (dlunch/wie + cores/wipi/wie_libretro) for Android arm64-v8a.
#
# Usage (from the project root):
#   bash cores/wipi/build.sh            # clone wie (pinned) -> add wie_libretro -> cargo build -> strip -> copy
#   bash cores/wipi/build.sh --clean    # also wipe the cargo target dir first
#   bash cores/wipi/build.sh --host     # build for this machine instead (RetroArch testing): cores/wipi/build/host/
#   bash cores/wipi/build.sh --test     # run the core's unit + smoke tests on this machine (no NDK needed)
#
# Output: app/src/main/jniLibs/arm64-v8a/libwipi_libretro.so
# Needs: git, a Rust toolchain (rustup recommended; 1.88+ for wie's edition-2024 code), the Android NDK.
set -euo pipefail

CORE_ID="wipi"
SRC_REPO="https://github.com/dlunch/wie"
SRC_COMMIT="25e2fd65b14959d2332bb7ea93d9899b38602d33"   # dlunch/wie main, 2026-09-30 (0.1.7)
NDK_VERSION="28.2.13676358"
ANDROID_ABI="arm64-v8a"
ANDROID_API="26"
RUST_TARGET="aarch64-linux-android"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SRC_DIR="$SCRIPT_DIR/src/wie"
CRATE_DIR="$SCRIPT_DIR/wie_libretro"
BUILD_DIR="$SCRIPT_DIR/build"
PATCH_DIR="$SCRIPT_DIR/patches"
OUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$ANDROID_ABI"
OUT_SO="$OUT_DIR/lib${CORE_ID}_libretro.so"

log() { printf '[%s] %s\n' "$CORE_ID" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$CORE_ID" "$*" >&2; exit 1; }

CLEAN=0
MODE="android"
for arg in "$@"; do
	case "$arg" in
		--clean) CLEAN=1 ;;
		--host) MODE="host" ;;
		--test) MODE="test" ;;
		*) die "unknown argument: $arg" ;;
	esac
done

command -v cargo >/dev/null || die "cargo not found (install Rust: https://rustup.rs)"
log "rustc   : $(rustc --version)"

# ---------------------------------------------------------------------------
# Source: wie at the pinned commit, patches, and our crate added to its workspace
# ---------------------------------------------------------------------------
if [[ ! -d "$SRC_DIR/.git" ]]; then
	log "cloning $SRC_REPO"
	rm -rf "$SRC_DIR"
	mkdir -p "$(dirname "$SRC_DIR")"
	# No CRLF conversion: patches/ are LF and must apply on Windows checkouts too.
	git -c core.autocrlf=false clone "$SRC_REPO" "$SRC_DIR"
	git -C "$SRC_DIR" config core.autocrlf false
fi
if [[ "$(git -C "$SRC_DIR" rev-parse HEAD)" != "$SRC_COMMIT" ]]; then
	git -C "$SRC_DIR" cat-file -e "$SRC_COMMIT^{commit}" 2>/dev/null || git -C "$SRC_DIR" fetch --all --tags
fi
# Always start from a pristine tree so re-runs are idempotent.
git -C "$SRC_DIR" reset --hard >/dev/null
git -C "$SRC_DIR" checkout --detach -q "$SRC_COMMIT"
git -C "$SRC_DIR" clean -fdx >/dev/null

shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
for p in "${PATCHES[@]}"; do
	log "applying patch $(basename "$p")"
	git -C "$SRC_DIR" apply --whitespace=nowarn "$p"
done

log "adding wie_libretro to the wie workspace"
mkdir -p "$SRC_DIR/wie-libretro"
cp -R "$CRATE_DIR/Cargo.toml" "$CRATE_DIR/quirks.toml" "$CRATE_DIR/src" "$CRATE_DIR/tests" "$SRC_DIR/wie-libretro/"
# members = [ ... ] -> insert our crate as the first member (awk: portable across GNU/BSD)
awk '{ print } /^members = \[/ { print "    \"wie-libretro\"," }' "$SRC_DIR/Cargo.toml" > "$SRC_DIR/Cargo.toml.new"
mv "$SRC_DIR/Cargo.toml.new" "$SRC_DIR/Cargo.toml"
grep -q '"wie-libretro"' "$SRC_DIR/Cargo.toml" || die "could not add wie-libretro to the workspace"

# Cargo output lives outside the clone so a fresh clone keeps the incremental build. Override with
# CARGO_TARGET_DIR when the project path has non-ASCII characters (MinGW's ld can't write there).
TARGET_DIR="${CARGO_TARGET_DIR:-$BUILD_DIR/target}"
export CARGO_TARGET_DIR="$TARGET_DIR"
if (( CLEAN )); then
	log "cleaning $BUILD_DIR"
	rm -rf "$BUILD_DIR"
fi

cd "$SRC_DIR"

if [[ "$MODE" == "test" ]]; then
	log "running tests"
	cargo test -p wie-libretro --release
	exit 0
fi

if [[ "$MODE" == "host" ]]; then
	log "building for the host"
	cargo build -p wie-libretro --release --lib --bins
	mkdir -p "$BUILD_DIR/host"
	for f in libwipi_libretro.so libwipi_libretro.dylib wipi_libretro.dll wipi_headless wipi_headless.exe; do
		[[ -f "$TARGET_DIR/release/$f" ]] && cp -f "$TARGET_DIR/release/$f" "$BUILD_DIR/host/"
	done
	cat > "$BUILD_DIR/host/wipi_libretro.info" <<'EOF'
display_name = "Feature phone (WIPI / wie)"
authors = "dlunch (wie), OneEmu"
supported_extensions = "zip|jar|jad"
corename = "WIPI (wie)"
license = "MIT"
permissions = ""
display_version = "0.1.7-oneemu"
categories = "Emulator"
systemname = "WIPI"
needs_fullpath = "true"
supports_no_game = "false"
savestate = "false"
EOF
	log "OK -> $BUILD_DIR/host"
	exit 0
fi

# ---------------------------------------------------------------------------
# Android toolchain
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

case "$(uname -s)" in
	Darwin) HOST_TAG="darwin-x86_64" ;;
	Linux)  HOST_TAG="linux-x86_64" ;;
	*) die "unsupported host: $(uname -s)" ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CLANG="$LLVM_BIN/${RUST_TARGET}${ANDROID_API}-clang"
[[ -x "$CLANG" ]] || die "NDK clang not found: $CLANG"
log "NDK     : $NDK"

if command -v rustup >/dev/null; then
	rustup target add "$RUST_TARGET" >/dev/null
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$LLVM_BIN/llvm-ar"
# 16 KB page alignment: required on Android 15+ devices, harmless on 4 KB ones.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"

log "building wie-libretro for $RUST_TARGET"
cargo build -p wie-libretro --release --lib --target "$RUST_TARGET"

BUILT_SO="$TARGET_DIR/$RUST_TARGET/release/libwipi_libretro.so"
[[ -f "$BUILT_SO" ]] || die "build did not produce $BUILT_SO"

# ---------------------------------------------------------------------------
# Strip + install + verify
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR" "$BUILD_DIR"
STRIPPED_SO="$BUILD_DIR/lib${CORE_ID}_libretro.so"
"$LLVM_BIN/llvm-strip" --strip-unneeded -o "$STRIPPED_SO" "$BUILT_SO"
cp -f "$STRIPPED_SO" "$OUT_SO"

ELF_HEADER="$("$LLVM_BIN/llvm-readelf" -h "$OUT_SO")"
grep -q AArch64 <<<"$ELF_HEADER" || die "output is not AArch64"
DYN_SYMS="$("$LLVM_BIN/llvm-nm" -D "$OUT_SO")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info retro_get_system_av_info retro_serialize_size; do
	grep -qE " T ${sym}$" <<<"$DYN_SYMS" || die "missing exported symbol: $sym"
done

# The SoundFont ships as a core asset (see fetch-soundfont.sh); fetch it too so a local APK build has it.
bash "$SCRIPT_DIR/fetch-soundfont.sh" || log "WARNING: SoundFont download failed; MIDI music will be silent"

log "OK -> $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
