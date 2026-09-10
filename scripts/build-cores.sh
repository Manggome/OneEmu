#!/usr/bin/env bash
# Build every bundled libretro core (cores/*/build.sh) for arm64-v8a.
#
# Usage (from anywhere):
#   scripts/build-cores.sh            # sequential, stop on first failure
#   scripts/build-cores.sh -j 3       # up to 3 cores in parallel (logs in cores/<id>/build/build.log)
#   scripts/build-cores.sh --clean    # pass --clean through to every build.sh
#   scripts/build-cores.sh mgba nes   # only the listed core ids
#
# Output: app/src/main/jniLibs/arm64-v8a/lib<id>_libretro.so and a summary table.
# Works with the bash 3.2 that ships with macOS as well as bash 5 on Ubuntu.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JOBS=1
CLEAN_ARG=""
SELECTED=""
while (($#)); do
	case "$1" in
		-j) JOBS="${2:-1}"; shift 2 ;;
		-j*) JOBS="${1#-j}"; shift ;;
		--clean) CLEAN_ARG="--clean"; shift ;;
		-h|--help) sed -n '2,11p' "$0"; exit 0 ;;
		*) SELECTED="$SELECTED $1"; shift ;;
	esac
done

CORES=""
for script in cores/*/build.sh; do
	id="$(basename "$(dirname "$script")")"
	if [[ -n "$SELECTED" ]]; then
		case " $SELECTED " in *" $id "*) ;; *) continue ;; esac
	fi
	CORES="$CORES $id"
done
[[ -n "$CORES" ]] || { echo "no cores found"; exit 1; }

OUT_DIR="app/src/main/jniLibs/arm64-v8a"
START_ALL=$(date +%s)

result_file() { echo "cores/$1/build/.result"; }

# Runs one core build, recording "<STATUS> <seconds>" in cores/<id>/build/.result.
build_core() {
	local id="$1" quiet="$2" t0 status
	mkdir -p "cores/$id/build"
	rm -f "$(result_file "$id")"
	t0=$(date +%s)
	if [[ "$quiet" == "1" ]]; then
		if bash "cores/$id/build.sh" $CLEAN_ARG >"cores/$id/build/build.log" 2>&1; then status=OK; else status=FAIL; fi
	else
		if bash "cores/$id/build.sh" $CLEAN_ARG; then status=OK; else status=FAIL; fi
	fi
	echo "$status $(( $(date +%s) - t0 ))" >"$(result_file "$id")"
	[[ "$status" == "OK" ]]
}

for id in $CORES; do rm -f "$(result_file "$id")"; done

if ((JOBS <= 1)); then
	for id in $CORES; do
		echo "==> building $id"
		if ! build_core "$id" 0; then
			echo "!!! $id failed; stopping." >&2
			break
		fi
	done
else
	echo "==> building cores with up to $JOBS parallel jobs (logs: cores/<id>/build/build.log)"
	pids=""
	running=0
	failed=0
	for id in $CORES; do
		((failed)) && break
		build_core "$id" 1 &
		pids="$pids $!"
		running=$((running + 1))
		if ((running >= JOBS)); then
			# bash 3.2 has no `wait -n`: wait for the whole batch.
			for p in $pids; do wait "$p" || failed=1; done
			pids=""; running=0
		fi
	done
	for p in $pids; do wait "$p" || failed=1; done
	((failed)) && echo "!!! at least one core failed; see cores/<id>/build/build.log" >&2
fi

echo
printf '%-14s %-6s %-8s %s\n' CORE STATUS TIME OUTPUT
printf '%-14s %-6s %-8s %s\n' ---- ------ ---- ------
overall=0
for id in $CORES; do
	st="SKIP"; sec="0"
	if [[ -f "$(result_file "$id")" ]]; then
		read -r st sec <"$(result_file "$id")"
		rm -f "$(result_file "$id")"
	fi
	so="$OUT_DIR/lib${id}_libretro.so"
	size="-"
	[[ -f "$so" ]] && size="$(du -h "$so" | cut -f1)"
	printf '%-14s %-6s %-8s %s\n' "$id" "$st" "${sec}s" "$so ($size)"
	[[ "$st" == "OK" ]] || overall=1
done
echo
echo "total: $(( $(date +%s) - START_ALL ))s"
exit $overall
