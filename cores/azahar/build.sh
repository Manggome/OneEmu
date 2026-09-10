#!/usr/bin/env bash
# OneEmu core build: Azahar (Nintendo 3DS) official libretro core -> arm64-v8a libazahar_libretro.so
#
# Usage (from project root):  bash cores/azahar/build.sh [--clean]
# Works on macOS (arm64/x86_64) and Ubuntu (GitHub Actions).
#
# Deviations from the generic core convention, all deliberate:
#  * Azahar requires CMake >= 3.25 (cmake_minimum_required in CMakeLists.txt); the SDK's
#    cmake/3.22.1 cannot configure it. We use a cmake >= 3.25 from PATH if one exists,
#    otherwise pip-install one into a venv under build/ (portable: no brew/apt/sdkmanager).
#    Ninja still comes from the SDK cmake/3.22.1 dir (or PATH).
#  * ENABLE_VULKAN=OFF: on Android the core ignores the frontend's preferred renderer and
#    forces Vulkan when built with Vulkan support; OneEmu only provides GLES 3.x HW render.
#  * Requires a *recursive* clone (~50 submodules, ~550 MB); shallow clone is used to save time.
set -euo pipefail

CORE_ID="azahar"
REPO_URL="https://github.com/azahar-emu/azahar"
TAG="2126.1"                                            # release tag (2026-09-09)
COMMIT="26e608f6fa292b27cda0ae8c84e148d17600a5e6"      # == tag 2126.1; bump deliberately after re-verifying the build
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
    if [ -n "$c" ] && [ -f "$c/build/cmake/android.toolchain.cmake" ]; then echo "$c"; return 0; fi
  done
  return 1
}
NDK="$(find_ndk)" || { echo "error: NDK $NDK_VERSION not found (set ANDROID_NDK_HOME)" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;   # NDK ships a universal darwin-x86_64 prebuilt (runs on arm64 Macs)
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
LLVM_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$LLVM_BIN" ] || { echo "error: toolchain not found at $LLVM_BIN" >&2; exit 1; }
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
  [ -n "$NINJA" ] || { echo "error: ninja not found (SDK cmake/3.22.1 or PATH)" >&2; exit 1; }
fi

# ---------------------------------------------------------------- CMake >= 3.25
# Returns 0 if "$1 --version" reports >= CMAKE_MIN.
cmake_is_new_enough() {
  local v
  v="$("$1" --version 2>/dev/null | head -n1 | sed -E 's/^cmake version ([0-9]+\.[0-9]+).*/\1/')" || return 1
  [ -n "$v" ] || return 1
  [ "$(printf '%s\n%s\n' "$CMAKE_MIN" "$v" | sort -t. -k1,1n -k2,2n | head -n1)" = "$CMAKE_MIN" ]
}

CMAKE=""
if [ "$CLEAN" = 1 ]; then rm -rf "$BUILD_DIR"; fi
mkdir -p "$BUILD_DIR"

PATH_CMAKE="$(command -v cmake || true)"
if [ -n "$PATH_CMAKE" ] && cmake_is_new_enough "$PATH_CMAKE"; then
  CMAKE="$PATH_CMAKE"
elif [ -x "$VENV_DIR/bin/cmake" ] && cmake_is_new_enough "$VENV_DIR/bin/cmake"; then
  CMAKE="$VENV_DIR/bin/cmake"
else
  echo "== No cmake >= $CMAKE_MIN on PATH; installing '$CMAKE_PIP_SPEC' into $VENV_DIR"
  PYTHON="$(command -v python3 || true)"
  [ -n "$PYTHON" ] || [ ! -x /opt/homebrew/bin/python3 ] || PYTHON=/opt/homebrew/bin/python3
  [ -n "$PYTHON" ] || { echo "error: python3 not found (needed to pip-install cmake >= $CMAKE_MIN)" >&2; exit 1; }
  rm -rf "$VENV_DIR"
  "$PYTHON" -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install --quiet --disable-pip-version-check "$CMAKE_PIP_SPEC"
  CMAKE="$VENV_DIR/bin/cmake"
  cmake_is_new_enough "$CMAKE" || { echo "error: pip cmake is still < $CMAKE_MIN" >&2; exit 1; }
fi

if command -v nproc >/dev/null 2>&1; then JOBS="$(nproc)"; else JOBS="$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"; fi

echo "== Azahar (3DS) libretro core build"
echo "   NDK:     $NDK ($HOST_TAG)"
echo "   CMake:   $CMAKE ($("$CMAKE" --version | head -n1))"
echo "   Ninja:   $NINJA"
echo "   Commit:  $COMMIT (tag $TAG)"
echo "   Jobs:    $JOBS"
echo "   Output:  $OUT_SO"

# ---------------------------------------------------------------- source checkout (pinned, recursive)
if [ ! -d "$SRC_DIR/.git" ]; then
  echo "== Cloning $REPO_URL @ $TAG (shallow, recursive)"
  rm -rf "$SRC_DIR"
  git clone --recursive --shallow-submodules --depth 1 --branch "$TAG" "$REPO_URL" "$SRC_DIR"
fi
if [ "$(git -C "$SRC_DIR" rev-parse HEAD)" != "$COMMIT" ]; then
  echo "== Checking out $COMMIT"
  git -C "$SRC_DIR" fetch --quiet --depth 1 origin "$COMMIT" 2>/dev/null || git -C "$SRC_DIR" fetch --quiet --tags origin
  git -C "$SRC_DIR" checkout --quiet --force --detach "$COMMIT"
  git -C "$SRC_DIR" submodule update --init --recursive --depth 1
  # Tree changed: force a fresh configure.
  rm -rf "$CMAKE_BUILD_DIR"
fi
# Make sure submodules are present even when src/ was pre-populated at the right commit.
git -C "$SRC_DIR" submodule update --init --recursive --depth 1 >/dev/null

# ---------------------------------------------------------------- patches (idempotent)
shopt -s nullglob
PATCHES=("$PATCH_DIR"/*.patch)
shopt -u nullglob
for p in ${PATCHES[@]+"${PATCHES[@]}"}; do
  if git -C "$SRC_DIR" apply --check --reverse "$p" >/dev/null 2>&1; then
    echo "== Patch already applied: $(basename "$p")"
  elif git -C "$SRC_DIR" apply --check "$p" >/dev/null 2>&1; then
    echo "== Applying patch: $(basename "$p")"
    git -C "$SRC_DIR" apply "$p"
  else
    echo "error: patch $(basename "$p") does not apply to $COMMIT" >&2; exit 1
  fi
done

# ---------------------------------------------------------------- configure
# Flags mirror .github/workflows/libretro.yml (libretro-android job) with these changes:
#   ANDROID_PLATFORM android-26 (OneEmu minimum; CI uses 21), Ninja generator,
#   ENABLE_VULKAN=OFF (see header), ENABLE_OPENGL=ON pinned (default, but the
#   citra_graphics_api=OpenGL option only exists when it is on),
#   ENABLE_TESTS=OFF (not built anyway; skips Catch2 configure),
#   CITRA_WARNINGS_AS_ERRORS=OFF (CI uses NDK 29 / clang 21; we use NDK 28 / clang 19).
mkdir -p "$CMAKE_BUILD_DIR" "$OUT_DIR"
"$CMAKE" -S "$SRC_DIR" -B "$CMAKE_BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_NDK="$NDK" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_LIBRETRO=ON \
  -DENABLE_OPENGL=ON \
  -DENABLE_VULKAN=OFF \
  -DENABLE_TESTS=OFF \
  -DCITRA_WARNINGS_AS_ERRORS=OFF

# ---------------------------------------------------------------- build
echo "== Building azahar_libretro (-j$JOBS)"
"$CMAKE" --build "$CMAKE_BUILD_DIR" --target azahar_libretro -j "$JOBS"

BUILT_SO="$CMAKE_BUILD_DIR/bin/Release/azahar_libretro.so"
[ -f "$BUILT_SO" ] || { echo "error: $BUILT_SO not produced" >&2; exit 1; }

# ---------------------------------------------------------------- strip + install
echo "== Stripping"
"$STRIP" --strip-unneeded -o "$OUT_SO" "$BUILT_SO"

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
