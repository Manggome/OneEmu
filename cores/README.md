# OneEmu 코어 빌드 규약

OneEmu는 libretro 코어를 arm64-v8a `.so`로 빌드해 APK에 포함합니다. 각 코어는 `cores/<id>/` 폴더 하나로 관리됩니다.

## 폴더 구조

```
cores/<id>/
  build.sh        # 필수. 소스 클론(고정 커밋) → 빌드 → 결과물 복사까지 한 번에 수행
  core.json       # 필수. 프론트엔드가 읽는 코어 메타데이터
  patches/*.patch # 선택. 소스 수정이 필요하면 패치 파일로 관리 (src/ 안을 직접 고치지 않음)
  src/            # build.sh가 클론하는 소스 (gitignored)
  build/          # 중간 산출물 (gitignored)
```

## build.sh 요구사항

- `bash cores/<id>/build.sh` 로 프로젝트 루트에서 실행. macOS와 Ubuntu(GitHub Actions) 모두에서 동작해야 함.
- NDK 경로: `$ANDROID_NDK_HOME` → `$ANDROID_HOME/ndk/28.2.13676358` → `~/Library/Android/sdk/ndk/28.2.13676358` 순으로 탐색.
- CMake/Ninja: `$ANDROID_HOME/cmake/3.22.1/bin` 우선, 없으면 PATH.
- 대상: `arm64-v8a`, `ANDROID_PLATFORM=android-26`, Release(-O2 이상), `llvm-strip`으로 심볼 제거.
- 소스는 `cores/<id>/src`에 **고정 커밋**으로 클론 (`git clone` 후 `git checkout <sha>`; 서브모듈이 있으면 `--recursive`). 이미 있으면 재사용.
- 결과물: `app/src/main/jniLibs/arm64-v8a/lib<id>_libretro.so` (반드시 `lib` 접두사 + `.so`).
- 멱등성: 두 번 실행해도 안전해야 함. `--clean` 인자를 받으면 build/ 삭제 후 재빌드.

## core.json 스키마

```json
{
  "id": "mgba",
  "displayName": "mGBA",
  "libFile": "libmgba_libretro.so",
  "systems": ["gba", "gbc", "gb"],
  "extensions": ["gba", "gb", "gbc", "sgb", "zip"],
  "needFullPath": false,
  "hwRender": "none",
  "bios": [{ "system": "gba", "file": "gba_bios.bin", "required": false, "description": "GBA BIOS (선택)" }],
  "sourceRepo": "https://github.com/libretro/mgba",
  "sourceCommit": "<sha>",
  "license": "MPL-2.0",
  "defaultOptions": { "mgba_skip_bios": "ON" },
  "notes": "한 줄 메모"
}
```

- `systems` 값은 아래 시스템 ID만 사용: `gb`, `gbc`, `gba`, `nds`, `nes`, `psp`, `ps2`, `3ds`, `arcade`.
- `hwRender`: `none`(소프트웨어 프레임버퍼), `gles3`, `vulkan`. 두 가지 지원 시 배열 대신 우선순위 높은 하나만.
- `needFullPath`: 코어가 `retro_system_info.need_fullpath`를 요구하면 true.
- `defaultOptions`: 프론트엔드가 `RETRO_ENVIRONMENT_GET_VARIABLE`로 넘겨줄 기본 코어 옵션. 안드로이드 모바일에 맞는 값(예: 스레드 렌더링, 해상도)을 넣는다.

## 검증

빌드 후 다음이 모두 참이어야 함:

```bash
NDK=~/Library/Android/sdk/ndk/28.2.13676358
BIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin   # Linux는 linux-x86_64
$BIN/llvm-readelf -h app/src/main/jniLibs/arm64-v8a/lib<id>_libretro.so | grep AArch64
$BIN/llvm-nm -D app/src/main/jniLibs/arm64-v8a/lib<id>_libretro.so | grep -E " T retro_(run|load_game|api_version|get_system_info)$"
```

## 금지 사항

- BIOS, ROM, 펌웨어 파일을 저장소나 APK에 포함하지 않는다.
- `git add/commit/push`, `./gradlew` 실행은 코어 빌드 스크립트나 에이전트가 하지 않는다 (통합 담당이 수행).
- `cores/<id>/`와 결과물 `.so` 외의 파일은 수정하지 않는다.
