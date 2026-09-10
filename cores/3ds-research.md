# Nintendo 3DS 에뮬레이션 도입 조사 (Android arm64)

조사일: 2026-09-10. 조사 범위: Azahar(공식 libretro 코어 + Android 앱), libretro/citra(레거시 코어), Panda3DS, Mikage.
소스는 스크래치패드에 클론해 직접 확인했으며 프로젝트 파일은 이 문서 외에 수정하지 않았습니다.

---

## 1. 결론 (추천 경로 1개)

**Azahar 업스트림의 공식 libretro 코어(`src/citra_libretro`, 빌드 타깃 `azahar_libretro`)를 OneEmu 코어 규약대로 소스 빌드해 `libazahar_libretro.so`로 포함한다.**

근거:

- 2026년 3월(2125.0 Alpha 4)부터 Azahar 본 저장소가 libretro 코어를 **1급 타깃**으로 유지한다. `.github/workflows/libretro.yml`에 Android arm64-v8a 잡이 있고, 매 릴리스(최신 2126.1, 2026-09-09)에 `azahar-libretro-android-arm64-v8a-<tag>.zip`이 첨부된다. 2125.0~2126.1 사이 libretro 관련 머지 PR만 32건이다.
- Android 빌드에서 `USING_GLES`가 정의되어 `RETRO_HW_CONTEXT_OPENGLES3` **3.2** 컨텍스트를 요청한다. OneEmu 프론트엔드(`app/src/main/cpp/frontend.cpp`)가 이미 GLES3 HW 렌더(`SET_HW_RENDER`, `GET_PREFERRED_HW_RENDER`, FBO 제공)를 구현하고 있어 아키텍처가 맞는다. Vulkan(negotiation interface, VK 1.1) 경로도 코어에 이미 있어 프론트엔드가 Vulkan을 갖추면 옵션만 바꿔 전환할 수 있다.
- 라이선스 GPLv2+ (`license.txt`). 사이드로드 APK + GitHub Releases 배포이므로 문제 없음(소스 공개 의무만 지키면 됨).
- BIOS/펌웨어 불필요: 내장 키 블롭(`ENABLE_BUILTIN_KEYBLOB=ON`)과 오픈소스 시스템 아카이브(`externals/open_source_archives`, 공유 폰트 등)가 포함된다. 단, **복호화된 ROM**만 실행 가능.
- 세이브스테이트(`retro_serialize/unserialize`) 구현 완료, 코어 옵션 V2, 메모리 맵(`SET_MEMORY_MAPS`), 입력 디스크립터 제공.
- 대안인 `libretro/citra`는 같은 계보(동일 작성자 warmenhoven)의 **2023-12 Citra 스냅샷**이라 Azahar에 2.5년치 정확도/성능 개선이 빠져 있고, Panda3DS는 세이브스테이트가 없고 호환성이 낮다. Azahar 안드로이드 앱 네이티브 라이브러리를 직접 임베드하는 방식은 JNI 결합도가 높아 libretro 경로보다 5~10배의 작업량이 든다(3절 참조).

빌드 시도 결과(요약): NDK 28.2.13676358 + CMake 4.4.3(pip) + Ninja로 `-DENABLE_LIBRETRO=ON` 구성 성공(1756 컴파일 스텝). 컴파일은 시간 제한 안에 끝나지 않아 백그라운드로 진행 중이며 에러는 없었다(진행 상황은 5절 끝 참조). 단 하나의 사전 조건 이슈: **Azahar는 CMake ≥ 3.25를 요구**하므로 OneEmu 규약의 SDK CMake 3.22.1로는 구성이 불가하다(해결책은 4절).

---

## 2. 옵션별 비교표

| 항목 | **Azahar libretro (추천)** | libretro/citra (레거시) | Panda3DS libretro | Mikage | Azahar Android 네이티브 직접 임베드 |
|---|---|---|---|---|---|
| 저장소 / 기준 커밋 | github.com/azahar-emu/azahar, 태그 `2126.1` = `26e608f6fa292b27cda0ae8c84e148d17600a5e6` (2026-09-09) | github.com/libretro/citra, `e4cbe379…` (2026-09-04) | git.libretro.com/libretro/Panda3DS 미러, `5aaa1d26…` (2026-08-11) | github.com/mikage-emu/mikage-dev | 동일 Azahar 저장소 `src/android` |
| 유지보수 상태 | 활발. 업스트림이 직접 유지, 6개 플랫폼 CI, 릴리스마다 코어 첨부 | 빌드 유지만 됨(libretro 인프라 팀: WizzardSK, cscd98, warmenhoven). 에뮬 코어 본체는 Citra 2023-12 스냅샷 + Vulkan 브랜치. 2026-07 NDK r29/Clang 21 fmt 빌드 수정 이력 | 활발하지만 초기 단계(많은 게임 그래픽 깨짐). libretro 코어는 부가 타깃 | 개발자 에디션만 공개(Linux, Conan/CMake). Android 앱은 공개 빌드 없음, libretro 없음 | 활발 |
| 라이선스 | GPLv2+ | GPLv2+ | GPLv3 | 명시 확인 못함(GPL 계열 추정) | GPLv2+ |
| Android arm64 빌드 가능성 | **공식 CI 있음** (NDK 29.0.14206865, API 21, `c++_static`, CMake 3.30.3). 로컬 NDK 28.2 + CMake 4.4로 구성 성공 | 공식 CI 있음(NDK 29). `cmake_minimum_required 3.15`라 SDK CMake 3.22로도 구성 가능 | gitlab CI에 android-arm64 잡 있음(NDK 26 고정) | 없음 | Gradle `externalNativeBuild`(NDK 27.3, minSdk 29, CMake 3.25+) 전용. 다른 앱에 이식하려면 CMake/JNI 대폭 수정 |
| 렌더 요구사항 | Android: **GLES 3.2** (`RETRO_HW_CONTEXT_OPENGLES3` 3.2) 또는 Vulkan 1.1(negotiation interface). 데스크톱: GL Core 4.3. 픽셀포맷 XRGB8888, `bottom_left_origin=true`, depth/stencil 불필요 | Android: GLES 3.2 또는 Vulkan(negotiation). 데스크톱 GL Core 3.3 | GL Core 4.1 / GLES3(`USING_GLES`); Vulkan 미완 | Vulkan 전용 | GLES(EGL) 또는 Vulkan(+adrenotools 커스텀 드라이버) |
| 세이브스테이트 | 지원(`SaveStateBuffer`, 비동기 커널 작업 드레인 로직 포함) | 지원(구버전 포맷) | **미지원** (`retro_serialize` → false) | 해당 없음 | 앱 자체 슬롯 방식(`saveState(slot)`) |
| 필요 시스템 파일 | 없음(내장 키 블롭 + 오픈소스 시스템 아카이브). 복호화 ROM 필수, 일부 게임은 Mii 데이터 | 동일 계보(구버전) | 없음 | – | 없음(선택적으로 홈 메뉴/시스템 타이틀 설치) |
| 예상 작업량 | **3~7일**: build.sh/core.json + 프론트엔드 소규모 수정 2건(FBO 크기, GLES 3.2 요청) + 실기 테스트 | 2~5일이지만 결과물이 열세(정확도/성능/버그 픽스 부족) | 3~6일 + 세이브스테이트 없음, 호환성 낮아 사용자 가치 낮음 | 수 주~수 개월(Android 포트 자체를 만들어야 함) | **3~6주**: 별도 `.so`(libcitra-android), `org.citra.citra_emu.*` 패키지에 결합된 JNI 심볼 70여 개, id_cache가 참조하는 Java 클래스 9개 재현, Surface/입력/설정(config.ini)/세이브스테이트 브리지 재작성, camera2ndk·mediandk·adrenotools 의존 |

---

## 3. 조사 상세

### 3.1 Azahar

**libretro 코어 존재 여부**: 있음. `src/citra_libretro/` (약 4,100줄: `citra_libretro.cpp`, `core_settings.cpp`, `environment.cpp`, `libretro_vk.cpp`, `emu_window/libretro_window.cpp`, `input/*`). CMake 옵션 `ENABLE_LIBRETRO=ON`이 Qt/SDL2/웹서비스/스크립팅/GDB/OpenAL/Room/cubeb/libusb를 강제로 OFF 한다. 결과물 이름은 `azahar_libretro.so`(`OUTPUT_NAME`), 편의 타깃 `azahar_libretro`. Android 전용 정의: `USING_GLES`, `HAVE_LIBRETRO_VFS`, `libretro_common` 링크, `-llog`.

**공식 Android 빌드 명령(CI 그대로)**:
```
cmake -DENABLE_LIBRETRO=ON -DANDROID_PLATFORM=android-21 \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_STL=c++_static -DANDROID_ABI=arm64-v8a . -B build/android-arm64-v8a
cmake --build build/android-arm64-v8a --target azahar_libretro --config Release
llvm-strip -s build/android-arm64-v8a/bin/Release/azahar_libretro.so
```

**프론트엔드가 제공해야 하는 libretro 환경(코어가 호출하는 것)**: `SET_HW_RENDER`, `GET_PREFERRED_HW_RENDER`, `SET_HW_SHARED_CONTEXT`, `SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE`(Vulkan일 때만), `SET_PIXEL_FORMAT(XRGB8888)`, `SET_CORE_OPTIONS_V2`/`SET_CORE_OPTIONS`/`SET_VARIABLES`, `GET_VARIABLE(_UPDATE)`, `GET_SYSTEM_DIRECTORY`, `GET_SAVE_DIRECTORY`, `GET_LOG_INTERFACE`, `SET_MEMORY_MAPS`, `SET_GEOMETRY`, `SET_FRAME_TIME_CALLBACK`, `SET_INPUT_DESCRIPTORS`, `SET_CONTROLLER_INFO`, `SET_SERIALIZATION_QUIRKS`, `GET_VFS_INTERFACE`(선택; 실패 시 libretro-common `filestream`이 내부 stdio 구현으로 폴백), `GET_SENSOR_INTERFACE`/`GET_MICROPHONE_INTERFACE`(선택), `GET_JIT_CAPABLE`(iOS 전용). OneEmu `frontend.cpp`는 이 중 Vulkan negotiation과 VFS/센서/마이크 외 전부를 이미 처리한다.

**렌더 선택 로직(중요)**: `environment.cpp::GetPreferredRenderer()`는 `#if defined(ANDROID) && defined(ENABLE_VULKAN)`이면 프론트엔드 선호를 **무시하고 Vulkan을 반환**한다. 코어 옵션 `citra_graphics_api`의 기본값이 `auto`이므로, Vulkan을 켠 채 빌드하면 OneEmu에서는 `SET_HW_RENDER(VULKAN)`가 거부되어 로드가 실패한다. 해결: (a) `-DENABLE_VULKAN=OFF`로 빌드하거나 (b) `defaultOptions`에 `"citra_graphics_api": "OpenGL"`을 넣는다. 1단계는 (a)+(b) 모두 적용을 권장한다.

**HW 렌더 파라미터**: GLES 시 `context_type=OPENGLES3, version 3.2`, `cache_context=false`, `bottom_left_origin=true`, depth/stencil 미요청. `context_reset()`에서 `gladLoadGLES2Loader(get_proc_address)`로 심볼을 받는다. `retro_get_system_av_info`는 현재 레이아웃 기준 base 크기(기본 Top-Bottom = 400x480)를 주지만 **`max_width/max_height`를 7200x9600**(10배 해상도 Side-by-Side)으로 보고한다.

**입력 매핑**: JOYPAD 전 버튼(A/B/X/Y/방향/L/R/L2=ZL/R2=ZR/START/SELECT/L3), ANALOG LEFT=서클패드, ANALOG RIGHT=C-스틱(옵션 `citra_analog_function`), 터치스크린은 `RETRO_DEVICE_POINTER`(옵션 `citra_enable_touch_touchscreen`) 또는 마우스. 코어가 레이아웃 안 하단 화면 영역으로 포인터 좌표를 스스로 변환한다.

**런타임 디렉터리**: `citra_use_libretro_save_path=LibRetro Default`(기본)이면 `GET_SAVE_DIRECTORY` (없으면 SYSTEM) + `/Azahar/`를 유저 디렉터리로 잡고 그 아래 `nand/`, `sdmc/`(가상 SD; 게임 세이브가 여기 들어감), `config/`, `shaders/`(디스크 셰이더 캐시)를 만든다. `retro_get_memory_data`는 NULL이므로 `.srm` 방식 세이브는 쓰지 않는다. 시스템 파일(BIOS) 요구 없음. `.cia` 설치 기능은 libretro 코어에 없으므로 지원 확장자는 `3ds|3dsx|z3dsx|elf|axf|cci|zcci|cxi|zcxi|app`(모두 `need_fullpath=true`).

**코어 옵션 키(접두어 `citra_`, `core_settings.h`의 `BOOST_HANA_STRING("citra_")`)와 기본값**: `citra_graphics_api=auto`, `citra_use_hw_shader`, `citra_use_shader_jit`, `citra_shaders_accurate_mul=enabled`, `citra_use_disk_shader_cache=enabled`, `citra_resolution_factor=1`, `citra_texture_filter=none`, `citra_texture_sampling=GameControlled`, `citra_use_cpu_jit=enabled`, `citra_cpu_clock_percentage=100`, `citra_is_new_3ds=New 3DS`, `citra_region_value=Auto`, `citra_language_value=English`, `citra_audio_emulation=hle`, `citra_input_type=auto`, `citra_layout_option=default`(Top-Bottom; `single_screen`, `large_screen`, `side_by_side` 등), `citra_swap_screen=Top`, `citra_swap_screen_mode=Toggle`, `citra_large_screen_proportion=4.00`, `citra_render_3d=off`, `citra_factor_3d=0`, `citra_analog_function=c_stick_and_touchscreen`, `citra_analog_deadzone=15`, `citra_enable_touch_touchscreen=enabled`, `citra_enable_mouse_touchscreen=enabled`, `citra_enable_touch_pointer_timeout=enabled`, `citra_enable_motion=enabled`, `citra_motion_sensitivity=1.0`, `citra_use_virtual_sd=enabled`, `citra_use_libretro_save_path=LibRetro Default`, `citra_custom_textures`, `citra_dump_textures`.

**Azahar Android 앱 아키텍처(직접 임베드 검토용)**:
- 단일 JNI 라이브러리 `libcitra-android.so` (`src/android/app/src/main/jni/CMakeLists.txt`의 `add_library(citra-android SHARED …)`). 링크: `audio_core citra_common citra_core input_common network` + `android camera2ndk jnigraphics log mediandk yuv inih`, GL이면 `EGL glad`, Vulkan이면 `adrenotools`(커스텀 Adreno 드라이버 로딩).
- Kotlin 표면 `org.citra.citra_emu.NativeLibrary`(`external fun` 약 70개): `setUserDirectory`, `createConfigFile`, `run(path)`(블로킹 루프, 별도 스레드), `surfaceChanged(Surface)`/`surfaceDestroyed`, `secondarySurfaceChanged`, `doFrame`, `pause/unPause/stopEmulation`, `onGamePadEvent/onGamePadMoveEvent/onGamePadAxisEvent`, `onTouchEvent/onTouchMoved`, `saveState(slot)`/`getSavestateInfo`, `initializeGpuDriver`, `reloadSettings`, `swapScreens`, `updateFramebuffer(isPortrait)` 등.
- 네이티브→Java 역호출: `id_cache.cpp`가 `NativeLibrary`, `NativeLibrary$CoreError`, `$InstallStatus`, `$SaveStateInfo`, `features/cheats/model/Cheat`, `model/GameInfo`, `utils/CiaInstallWorker`, `utils/DiskShaderCacheProgress(+$LoadCallbackStage)` 9개 클래스를 FindClass 하고 메서드 ID 10개를 캐시한다. JNI 함수 이름이 패키지에 묶여 있어(`Java_org_citra_citra_1emu_NativeLibrary_*`) OneEmu에 넣으려면 같은 패키지명을 유지하거나 소스를 패치해야 한다.
- 런타임: 유저 디렉터리(`config/config.ini`, `nand`, `sdmc`, `sysdata`, `shaders`, `log`) 필요, 설정은 ini 파일 경유(`Config` 클래스), 화면은 ANativeWindow 직접 사용, 오디오 cubeb/OpenSL.
- 결론: 기술적으로 가능하지만 libretro 코어와 중복되는 기능을 다시 만들어야 하며(입력/오디오/세이브스테이트/설정 브리지), Gradle/CMake 통합과 패키지 결합도 때문에 3~6주 규모. libretro 코어가 공식 지원되는 지금은 선택할 이유가 없다.

### 3.2 libretro/citra (레거시)

- 마지막 커밋 2026-09-04(CI 수정), 실질적 에뮬 코어 변경은 2024-01 "Revert Update to upstream" 이후 없음. `src/core`의 마지막 비-libretro 커밋은 2023-12(Citra 종료 직전 스냅샷 + `vulkan` 브랜치 병합).
- Android arm64: gitlab CI `android-arm64-v8a`가 NDK 29.0.14206865를 사용하고, 2026-07에 Clang 21용 fmt consteval 수정이 들어갔다. `build-libretro-android.sh`도 있다. `cmake_minimum_required 3.15`라 SDK CMake 3.22.1로 구성 가능 → OneEmu 규약에 가장 잘 맞는 건 이 코어다.
- 렌더: Android `USING_GLES` → `RETRO_HW_CONTEXT_OPENGLES3` 3.2; 데스크톱 GL Core 3.3; Vulkan negotiation 지원(`src/citra_libretro/vulkan/vk_swapchain.cpp`).
- 판단: 빌드는 될 가능성이 높지만 Azahar 대비 2.5년치 수정(GPU 타이밍, 오디오 HLE 개선, 게임별 세팅, 크래시 수정)이 없고, 옵션 키/세이브스테이트 포맷도 다르다. Azahar 코어 빌드가 어떤 이유로 막힐 때의 **폴백**으로만 취급.

### 3.3 Panda3DS

- `BUILD_LIBRETRO_CORE=ON` → `panda3ds_libretro.so`(`src/libretro_core.cpp`, 418줄). GPLv3. Android에서는 `OPENGL_PROFILE=OpenGLES`가 기본이고 libretro 코어도 `RETRO_HW_CONTEXT_OPENGLES3` 폴백을 갖는다. gitlab CI에 android-arm64 잡(NDK 26 고정) 있음.
- 결정적 한계: `retro_serialize_size`가 0, `retro_serialize/unserialize`가 false → **세이브스테이트 없음**. HLE 오디오 초기 단계, 다수 게임 그래픽 깨짐. 장점은 빌드가 가볍고 빠르다는 점 정도. 2순위 이하.

### 3.4 Mikage

- 2025-01 공개된 개발자 에디션은 Linux + Vulkan + Conan 2 빌드 전용. Android 앱은 별도 배포(오픈소스 빌드 경로 없음), libretro 인터페이스 없음. 현 시점 OneEmu 통합 대상 아님.

---

## 4. 추천 경로 단계별 통합 계획 (Azahar libretro)

### 단계 0. 프론트엔드 사전 수정 (OneEmu `app/src/main/cpp`, 통합 담당)
1. **HW FBO 크기**: `VideoGL::createSurface()`가 `max_width/max_height`로 FBO 텍스처를 만들면 Azahar의 7200x9600 → RGBA8 276MB 할당 시도. `base_width/height`(또는 `SET_GEOMETRY` 갱신값)에 여유 배수를 곱하거나 상한(예: 4096)으로 클램프하고, `SET_GEOMETRY`/`SET_SYSTEM_AV_INFO` 시 재할당하도록 변경.
2. **GLES 3.2 컨텍스트**: `video_gl.cpp`가 `EGL_CONTEXT_CLIENT_VERSION 3`만 지정한다. 대부분 드라이버가 최고 3.x를 주지만, `EGL_CONTEXT_MAJOR_VERSION 3 / MINOR 2`를 먼저 시도하고 실패 시 3.1/3.0으로 폴백하며 `SET_HW_RENDER`에서 코어가 요청한 `version_major/minor`보다 낮으면 경고를 남기도록 한다. Azahar GLES 렌더러는 3.2 기능(지오메트리 셰이더 등)에 의존한다.
3. (선택) `GET_VFS_INTERFACE`는 계속 false여도 동작한다. 단, 코어가 `need_fullpath=true`이므로 ROM은 실제 파일 경로(앱 전용 저장소나 SAF 복사본)로 전달해야 한다.
4. 3DS 세이브 표시: `.srm` 파일이 생기지 않고 `<saveDir>/Azahar/sdmc/…`에 저장되므로 세이브 관리 UI가 있다면 예외 처리.

### 단계 1. `cores/azahar/build.sh`
- `SRC_REPO=https://github.com/azahar-emu/azahar`, `SRC_COMMIT=26e608f6fa292b27cda0ae8c84e148d17600a5e6`(태그 2126.1), `git clone --recursive` 필수(서브모듈 약 400MB; 얕은 서브모듈 `--depth 1`로 시간 절약 가능).
- **CMake ≥ 3.25 요구**: 규약의 SDK `cmake/3.22.1`로는 구성 실패. build.sh에서 `cmake --version`을 검사해 3.25 미만이면 (a) `$ANDROID_HOME/cmake/3.30.3/bin`(sdkmanager `"cmake;3.30.3"`로 설치, Azahar CI와 동일) → (b) PATH의 cmake → (c) `python3 -m venv build/tools && pip install cmake ninja` 순으로 탐색하도록 규약을 이 코어에 한해 확장한다(README에 예외 명시 필요).
- 구성 플래그(로컬에서 구성 성공 확인):
  ```
  -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release -DENABLE_LIBRETRO=ON -DENABLE_TESTS=OFF
  -DENABLE_VULKAN=OFF            # 1단계: GLES만. 프론트엔드 Vulkan 지원 후 ON
  -DCITRA_WARNINGS_AS_ERRORS=OFF # NDK 28(Clang 19) 경고 차이 방어
  ```
  타깃 `azahar_libretro`, 산출물 `build/bin/Release/azahar_libretro.so` → `llvm-strip --strip-unneeded` → `app/src/main/jniLibs/arm64-v8a/libazahar_libretro.so`.
- NDK 28.2는 arm64 기본 `max-page-size=16384`라 16KB 페이지 요건 충족(Azahar CI는 같은 이유로 NDK 29 사용).
- 빌드 시간: 1,756 스텝, 8코어 macOS에서 60~100분 예상. GitHub Actions에서는 `build/` 캐시 또는 ccache를 권장.
- 패치 후보(필요 시 `patches/`): (1) Android 기본 Vulkan 강제(`GetPreferredRenderer`)를 프론트엔드 선호 우선으로 바꾸는 소규모 패치 — `ENABLE_VULKAN=OFF`로 빌드하면 불필요. (2) NDK 28에서 컴파일 에러가 나오면 해당 파일만 패치(현재까지 에러 없음).

### 단계 2. `cores/azahar/core.json`
```json
{
  "id": "azahar",
  "displayName": "Azahar (3DS)",
  "libFile": "libazahar_libretro.so",
  "systems": ["3ds"],
  "extensions": ["3ds", "cci", "cxi", "app", "3dsx", "elf", "axf", "zcci", "zcxi", "z3dsx"],
  "needFullPath": true,
  "hwRender": "gles3",
  "bios": [],
  "sourceRepo": "https://github.com/azahar-emu/azahar",
  "sourceCommit": "26e608f6fa292b27cda0ae8c84e148d17600a5e6",
  "license": "GPL-2.0-or-later",
  "defaultOptions": {
    "citra_graphics_api": "OpenGL",
    "citra_use_hw_shader": "enabled",
    "citra_use_shader_jit": "enabled",
    "citra_shaders_accurate_mul": "disabled",
    "citra_use_disk_shader_cache": "enabled",
    "citra_resolution_factor": "1",
    "citra_use_cpu_jit": "enabled",
    "citra_is_new_3ds": "New 3DS",
    "citra_audio_emulation": "hle",
    "citra_layout_option": "default",
    "citra_analog_function": "c_stick_and_touchscreen",
    "citra_enable_touch_touchscreen": "enabled",
    "citra_enable_motion": "disabled",
    "citra_use_virtual_sd": "enabled",
    "citra_use_libretro_save_path": "LibRetro Default"
  },
  "notes": "Azahar 2126.1 공식 libretro 코어. GLES 3.2 HW 렌더, 복호화 ROM만 지원(.cia 불가), BIOS 불필요. 세이브는 <saveDir>/Azahar/sdmc 아래 가상 SD에 저장."
}
```
값 문자열(`enabled`/`OpenGL`/`New 3DS`/`hle`/`default`)은 `core_settings.cpp`의 정의와 일치함을 확인했으나, 통합 시 코어가 `SET_CORE_OPTIONS_V2`로 넘기는 실제 값 목록과 다시 대조할 것. `shaders_accurate_mul`은 모바일 성능을 위해 끄는 것을 권장(정확도 문제 있는 게임만 켬).

### 단계 3. 검증
1. README 검증 명령(`llvm-readelf -h … AArch64`, `llvm-nm -D … retro_run 등`) 통과.
2. 실기(GLES 3.2 지원 기기, Android 8+ 대부분) 로그에서 `Using OpenGL hw renderer`, glad 로드 성공, `User dir set to ".../Azahar/"` 확인.
3. 홈브류(`3dsx`) → 상용 복호화 `.3ds`/`.cci` 순으로 부팅, 터치(POINTER) 하단 화면 매핑, 세이브스테이트 저장/복원, 앱 재시작 후 디스크 셰이더 캐시 재사용 확인.
4. 메모리: 3DS 코어는 1GB 이상 사용 가능 → `largeHeap`/네이티브 메모리 여유 확인.

### 단계 4. 후속(선택)
- 프론트엔드 Vulkan HW 렌더(negotiation interface v2) 구현 후 `ENABLE_VULKAN=ON` + `citra_graphics_api=Vulkan`으로 전환(Azahar가 Android에서 Vulkan을 권장하는 이유는 Adreno/Mali에서의 성능).
- 빌드 시간이 CI에 부담이면, 공식 릴리스 자산 `azahar-libretro-android-arm64-v8a-<tag>.zip`(약 11MB, attestations 첨부)을 내려받아 검증 후 사용하는 대체 스크립트를 고려. 다만 이는 README의 "고정 커밋 소스 빌드" 규약과 어긋나므로 프로젝트 차원의 결정이 필요.
- 센서(`GET_SENSOR_INTERFACE`, 자이로/가속도)와 마이크 인터페이스는 코어가 선택적으로 사용하므로 프론트엔드에 추가하면 모션 게임 지원이 늘어난다.

---

## 5. 위험 요소

| 위험 | 영향 | 완화 |
|---|---|---|
| `max_width/height` 7200x9600 보고 | 현재 프론트엔드 FBO 할당 방식이면 OOM/FBO 생성 실패 | 단계 0-1 (base 기준 할당 + 클램프 + SET_GEOMETRY 재할당) |
| Android에서 Vulkan 강제 기본값 | `ENABLE_VULKAN=ON`이면 OneEmu에서 로드 실패 | 1단계 `ENABLE_VULKAN=OFF` + `citra_graphics_api=OpenGL` |
| GLES 3.2 요구 | 3.1까지만 지원하는 구형 Mali/PowerVR 기기에서 셰이더 컴파일 실패 | 지원 기기 최소 사양 명시, EGL 3.2 명시 요청 + 실패 시 사용자 메시지 |
| CMake ≥ 3.25 요구 | 규약(SDK 3.22.1)과 충돌 | build.sh에서 3.30.3/PATH/pip 순 탐색, README 예외 기재 |
| 빌드 시간(60~100분) 및 서브모듈 400MB | CI 타임아웃, 개발자 반복 속도 | ccache/`build/` 캐시, 얕은 서브모듈, 필요 시 공식 릴리스 바이너리 폴백 |
| NDK 28 vs 업스트림 NDK 29 | Clang 19/21 차이로 경고→에러 가능 | `CITRA_WARNINGS_AS_ERRORS=OFF`, 필요 시 소규모 패치. 현재 구성 성공, 컴파일 중 에러 0 |
| 복호화 ROM만 지원, `.cia` 불가 | 사용자 혼란 | 확장자 목록 제한, 안내 문구 |
| 세이브 위치가 `.srm`이 아닌 가상 SD | 세이브 백업/동기화 UI가 있다면 누락 | `<saveDir>/Azahar/` 디렉터리 단위로 취급 |
| 세이브스테이트 포맷이 코어 버전에 종속 | 코어 업데이트 시 기존 스테이트 무효 | 코어 업데이트 시 릴리스 노트에 명시, 인게임 세이브 권장 |
| 옵션 키 접두어 `citra_` → 향후 `azahar_`로 바뀔 가능성 | defaultOptions 무효화 | 커밋 고정, 업데이트 시 `core_settings.h` 재확인 |
| 성능: JIT(dynarmic) + 하드웨어 셰이더라도 중급 기기에서는 풀스피드 불가한 게임 존재 | 사용자 기대치 | `resolution_factor=1`, `shaders_accurate_mul=disabled` 기본, 후속으로 Vulkan 전환 |
| GPLv2+ 배포 의무 | 소스 제공 필요 | 릴리스 노트에 Azahar 저장소·커밋·패치 링크 명시(이미 `core.json`에 기록) |
| VFS 미제공 | `content://` URI 직접 전달 불가 | 실제 파일 경로만 전달(현 구조와 동일) |

### 빌드 시도 로그(참고)
- 위치: 스크래치패드 `3ds/azahar/build/android-arm64/` (프로젝트 외부).
- 구성: `cmake -G Ninja -DENABLE_LIBRETRO=ON -DENABLE_TESTS=OFF -DCMAKE_BUILD_TYPE=Release -DANDROID_PLATFORM=android-26 -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static -DCMAKE_TOOLCHAIN_FILE=<NDK 28.2.13676358>/build/cmake/android.toolchain.cmake -DCITRA_WARNINGS_AS_ERRORS=OFF` → 성공(약 70초, CMake 4.4.3/pip, 경고만 발생: sirit의 구식 `cmake_minimum_required`).
- 빌드: `cmake --build … --target azahar_libretro -j8` 진행 중(문서 작성 시점 1756 스텝 중 초반 externals 단계 통과, 에러 0). 최종 결과는 아래 "빌드 결과" 절에 추가.

