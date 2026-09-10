#!/usr/bin/env bash
# OneEmu core build: melonDS DS (Nintendo DS) libretro core -> Android arm64-v8a
# Usage (from project root):  bash cores/melondsds/build.sh [--clean]
set -euo pipefail

CORE_ID="melondsds"
REPO_URL="https://github.com/JesseTG/melonds-ds"
REPO_TAG="v1.3.1"
REPO_COMMIT="bc4e4b67d2d470d7c682810a1e892cafd6f9082b"   # == ${REPO_TAG}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SRC_DIR="${SCRIPT_DIR}/src"
BUILD_DIR="${SCRIPT_DIR}/build"
PATCH_DIR="${SCRIPT_DIR}/patches"
OUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
OUT_SO="${OUT_DIR}/lib${CORE_ID}_libretro.so"

ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="android-26"
NDK_VERSION="28.2.13676358"

log() { printf '\n[%s] %s\n' "${CORE_ID}" "$*"; }

# ---------------------------------------------------------------- args
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- NDK
if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
  NDK="${ANDROID_NDK_HOME}"
elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/${NDK_VERSION}" ]]; then
  NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
elif [[ -d "${HOME}/Library/Android/sdk/ndk/${NDK_VERSION}" ]]; then
  NDK="${HOME}/Library/Android/sdk/ndk/${NDK_VERSION}"
else
  echo "Android NDK ${NDK_VERSION} not found (set ANDROID_NDK_HOME or ANDROID_HOME)." >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "Unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
LLVM_BIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake"
[[ -f "${TOOLCHAIN_FILE}" ]] || { echo "Missing ${TOOLCHAIN_FILE}" >&2; exit 1; }

# ---------------------------------------------------------------- CMake / Ninja
CMAKE_BIN_DIR=""
for cand in "${ANDROID_HOME:-}/cmake/3.22.1/bin" "${HOME}/Library/Android/sdk/cmake/3.22.1/bin" "${HOME}/Android/Sdk/cmake/3.22.1/bin"; do
  if [[ -n "${cand}" && -x "${cand}/cmake" ]]; then CMAKE_BIN_DIR="${cand}"; break; fi
done
if [[ -n "${CMAKE_BIN_DIR}" ]]; then
  CMAKE="${CMAKE_BIN_DIR}/cmake"
  NINJA="${CMAKE_BIN_DIR}/ninja"
  [[ -x "${NINJA}" ]] || NINJA="$(command -v ninja || true)"
else
  CMAKE="$(command -v cmake || true)"
  NINJA="$(command -v ninja || true)"
fi
[[ -x "${CMAKE:-}" ]] || { echo "cmake not found" >&2; exit 1; }
[[ -x "${NINJA:-}" ]] || { echo "ninja not found" >&2; exit 1; }

if [[ "$(uname -s)" == "Darwin" ]]; then JOBS="$(sysctl -n hw.ncpu)"; else JOBS="$(nproc)"; fi

log "NDK:   ${NDK}"
log "CMake: ${CMAKE}  Ninja: ${NINJA}  jobs: ${JOBS}"

# ---------------------------------------------------------------- source (pinned)
if [[ ! -d "${SRC_DIR}/.git" ]]; then
  log "Cloning ${REPO_URL} (${REPO_TAG} @ ${REPO_COMMIT})"
  rm -rf "${SRC_DIR}"
  git clone --recursive "${REPO_URL}" "${SRC_DIR}"
fi
if [[ "$(git -C "${SRC_DIR}" rev-parse HEAD)" != "${REPO_COMMIT}" ]]; then
  git -C "${SRC_DIR}" fetch --tags origin
fi
# Reset to the pristine pinned commit so patch application is idempotent.
git -C "${SRC_DIR}" checkout -q --force "${REPO_COMMIT}"
git -C "${SRC_DIR}" submodule update --init --recursive
git -C "${SRC_DIR}" checkout -q -- .
log "Source at $(git -C "${SRC_DIR}" rev-parse --short HEAD) ($(git -C "${SRC_DIR}" describe --tags --always))"

# ---------------------------------------------------------------- patches
shopt -s nullglob
PATCHES=("${PATCH_DIR}"/*.patch)
shopt -u nullglob
if (( ${#PATCHES[@]} > 0 )); then
  for p in "${PATCHES[@]}"; do
    log "Applying patch $(basename "$p")"
    git -C "${SRC_DIR}" apply --whitespace=nowarn "$p"
  done
else
  log "No patches to apply"
fi

# ---------------------------------------------------------------- configure
if (( CLEAN )); then
  log "--clean: removing ${BUILD_DIR}"
  rm -rf "${BUILD_DIR}"
fi
mkdir -p "${BUILD_DIR}"

# Flags mirror upstream .github/workflows/main.yaml "android" job:
#   -DENABLE_OGLRENDERER=OFF -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=... -DCMAKE_TOOLCHAIN_FILE=android.toolchain.cmake
# OpenGL is disabled on Android upstream (renderer not ported yet; issue #23). The software
# renderer (with ENABLE_THREADED_RENDERER, default ON) is what ships. Tests/Qt/Tracy are off.
log "Configuring (${ANDROID_ABI}, ${ANDROID_PLATFORM}, Release)"
"${CMAKE}" -S "${SRC_DIR}" -B "${BUILD_DIR}" -G Ninja \
  -Wno-deprecated \
  -DCMAKE_MAKE_PROGRAM="${NINJA}" \
  -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN_FILE}" \
  -DANDROID_ABI="${ANDROID_ABI}" \
  -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DENABLE_OPENGL=OFF \
  -DENABLE_OGLRENDERER=OFF \
  -DENABLE_THREADED_RENDERER=ON \
  -DENABLE_JIT=ON \
  -DBUILD_TESTING=OFF \
  -DTRACY_ENABLE=OFF \
  -DBUILD_QT_SDL=OFF

# ---------------------------------------------------------------- build
log "Building"
"${CMAKE}" --build "${BUILD_DIR}" --parallel "${JOBS}"

BUILT_SO="${BUILD_DIR}/src/libretro/melondsds_libretro_android.so"
[[ -f "${BUILT_SO}" ]] || { echo "Build output not found: ${BUILT_SO}" >&2; exit 1; }

# ---------------------------------------------------------------- strip + install
mkdir -p "${OUT_DIR}"
log "Stripping -> ${OUT_SO}"
"${LLVM_BIN}/llvm-strip" --strip-unneeded -o "${OUT_SO}" "${BUILT_SO}"

# ---------------------------------------------------------------- verify
log "Verifying"
# Capture tool output first: "tool | grep -q" under pipefail can fail spuriously (SIGPIPE).
ELF_HEADER="$("${LLVM_BIN}/llvm-readelf" -h "${OUT_SO}")"
grep -q AArch64 <<<"${ELF_HEADER}" || { echo "Not an AArch64 ELF" >&2; exit 1; }
DYN_SYMS="$("${LLVM_BIN}/llvm-nm" -D "${OUT_SO}")"
for sym in retro_run retro_load_game retro_api_version retro_get_system_info; do
  grep -qE " T ${sym}$" <<<"${DYN_SYMS}" || { echo "Missing exported symbol: ${sym}" >&2; exit 1; }
done
grep -E " T retro_(run|load_game|api_version|get_system_info)$" <<<"${DYN_SYMS}"
ls -la "${OUT_SO}"
log "OK: ${OUT_SO}"
