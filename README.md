# OneEmu

여러 기종을 하나의 앱으로 즐기는 안드로이드용 libretro 에뮬레이터 프론트엔드입니다. Kotlin + Jetpack Compose(Material 3)로 만들었고, 코어는 arm64-v8a용 `.so`로 빌드해 APK에 함께 넣습니다. Play 스토어에는 올리지 않으며 GitHub Releases에서 APK를 직접 내려받아 설치합니다.

> OneEmu에는 게임 ROM이나 BIOS가 **포함되어 있지 않습니다.** 직접 소유한 게임과 기기에서 추출한 파일만 사용하세요.

## 소개

| 기종 | 코어 | 상태 |
| --- | --- | --- |
| 게임보이 어드밴스 · 게임보이 · 게임보이 컬러 | mGBA | 안정 |
| 닌텐도 패미컴 (NES / FDS) | FCEUmm | 안정 |
| 닌텐도 DS | melonDS DS | 안정 (소프트웨어 렌더러) |
| 플레이스테이션 포터블 | PPSSPP | 실험적 (HW 렌더, OpenGL ES 3) |
| 플레이스테이션 2 | Play! | 실험적 (호환성 낮음) |
| 아케이드 | MAME 2003-Plus | 안정 (MAME 0.78 롬셋) |
| 닌텐도 3DS | Azahar | 실험적 (HW 렌더, OpenGL ES 3.2, 복호화된 롬만 지원) |

주요 기능

- 폴더 스캔으로 게임 자동 인식, 기종별 라이브러리 (리스트 / 그리드)
- 기종별로 위치를 편집할 수 있는 가상 패드, 물리 게임패드 지원
- 상태 저장 9슬롯 + 자동 저장, 빨리감기, 치트, 스크린샷
- 코어 옵션을 앱 안에서 편집 (코어/BIOS → 코어 옵션)
- GitHub Releases 기반 앱 내 업데이트

## 설치 방법

1. [Releases](https://github.com/Manggome/OneEmu/releases) 페이지에서 최신 `OneEmu-v<버전>-arm64.apk`를 내려받습니다. (64비트 ARM 기기, Android 8.0 이상)
2. APK를 열면 "알 수 없는 앱 설치" 허용을 요구합니다. 사용한 브라우저/파일 앱에 대해 **이 출처 허용**을 켜고 설치합니다.
3. 처음 실행하면 게임 폴더를 읽기 위한 **모든 파일 접근** 권한을 요청합니다. 설정 화면에서 OneEmu를 허용해 주세요.

> 디버그 빌드(`./gradlew assembleDebug`)는 패키지 이름이 `com.manggome.oneemu.debug`이고 버전에 `-debug`가 붙기 때문에, 앱 내 업데이트로 받은 릴리스 APK가 그 위에 덮어 설치되지 않습니다. 일반 사용 시에는 **릴리스 APK를 설치해서 사용하세요.**

## 게임 추가 방법

1. 게임 파일을 휴대폰 저장공간의 아무 폴더에 넣습니다. 기종별로 폴더를 나눠 두면 인식이 정확해집니다. 예: `내장 저장공간/Games/GBA/`, `Games/NDS/`, `Games/PSP/`
2. OneEmu → 라이브러리 → **폴더** 에서 해당 폴더를 추가하면 하위 폴더까지 스캔합니다.
3. 지원 확장자
   - GBA/GB/GBC: `gba`, `gb`, `gbc`, `sgb`, `zip`
   - NES: `nes`, `fds`, `unf`, `unif`, `zip`
   - NDS: `nds`, `dsi`, `ids`, `zip`
   - PSP: `iso`, `cso`, `pbp`, `chd`, `elf`, `prx`, `zip`(안에 ISO/CSO가 바로 들어 있는 경우)
   - PS2: `iso`, `chd`, `cso`, `isz`, `cue`, `elf`
   - 아케이드: MAME 2003-Plus 전용 롬셋 `zip` (파일 이름 = MAME 짧은 이름, 예: `sf2.zip`)

## BIOS 넣는 위치

BIOS/펌웨어는 앱 전용 폴더에 넣습니다. 파일 관리자로 아래 경로에 복사하거나, 앱의 **설정 → 코어 / BIOS → BIOS 파일 가져오기** 로 선택해 복사할 수 있습니다. 같은 화면에서 파일이 인식되었는지(✓ / ✗) 확인할 수 있습니다.

```
Android/data/com.manggome.oneemu/files/system/
```

| 기종 | 코어 | 파일 (system/ 기준) | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| GBA | mGBA | `gba_bios.bin` | 선택 | 없으면 내장 HLE BIOS 사용 |
| GB | mGBA | `gb_bios.bin` | 선택 | Game Boy 부트 ROM |
| GBC | mGBA | `gbc_bios.bin` | 선택 | Game Boy Color 부트 ROM |
| GB (SGB) | mGBA | `sgb_bios.bin` | 선택 | Super Game Boy 부트 ROM |
| NES (FDS) | FCEUmm | `disksys.rom` | 선택 | 패미컴 디스크 시스템 게임(.fds)에만 필요 |
| NDS | melonDS DS | `bios7.bin`, `bios9.bin`, `firmware.bin` | 선택 | 없으면 내장 FreeBIOS/펌웨어 사용 |
| NDS (DSi 모드) | melonDS DS | `dsi_bios7.bin`, `dsi_bios9.bin`, `dsi_firmware.bin`, `dsi_nand.bin` | 선택 | DSi 모드에서만 필요 |
| PSP | PPSSPP | 없음 | – | BIOS 불필요. 필요한 에셋은 앱이 `system/PPSSPP/`에 자동 설치 |
| PS2 | Play! | 없음 | – | HLE 방식이라 BIOS 불필요 |
| 3DS | Azahar | 없음 | – | 내장 키/오픈소스 시스템 아카이브 사용 |
| 아케이드 | MAME 2003-Plus | `mame2003-plus/cheat.dat`, `hiscore.dat`, `history.dat` | 선택 | 치트 / 하이스코어 / 히스토리 DB |

- 3DS(Azahar)는 BIOS가 필요 없지만 **복호화된 롬**(.3ds/.cci/.cxi/.app)만 실행됩니다. .cia 설치는 지원하지 않습니다. 세이브는 `saves/3ds/Azahar/` 아래에 저장됩니다.
- Android 11 이상에서는 일부 파일 관리자가 `Android/data`에 쓰지 못합니다. 그 경우 앱 안의 **BIOS 파일 가져오기**를 사용하세요.

## 앱 내 업데이트

- 앱을 열 때(6시간에 한 번) GitHub의 최신 릴리스를 확인하고, 새 버전이 있으면 릴리스 노트와 함께 알려 줍니다. **업데이트**를 누르면 APK를 내려받아 시스템 설치 화면으로 넘어갑니다.
- 처음 설치할 때 OneEmu에 "알 수 없는 앱 설치" 허용을 켜야 합니다. 앱이 설정 화면으로 안내합니다.
- **나중에**는 다음 확인 때 다시 알리고, **이 버전 건너뛰기**는 해당 버전을 다시 알리지 않습니다.
- 자동 확인은 설정 → 기타 → **시작할 때 업데이트 확인**에서 끌 수 있고, 설정 → 정보 → **업데이트 확인**으로 수동 확인할 수 있습니다.
- 버전 규칙: 앱 버전은 `0.1.<커밋 수>`이고 릴리스 태그는 `v0.1.<커밋 수>`입니다. 업데이터는 이 둘을 숫자 단위로 비교합니다.
- 릴리스 APK와 다른 키로 서명된 APK(디버그 빌드, 서명 secrets 없이 만든 CI 빌드) 위에는 덮어 설치할 수 없습니다. 그럴 때는 삭제 후 다시 설치해야 합니다(세이브는 `Android/data/.../files/saves`에 있으니 미리 백업).

## 직접 빌드하기

필요한 것

- JDK 17
- Android SDK: `platforms;android-37`, `build-tools;35.0.0`, `cmake;3.22.1`, **NDK 28.2.13676358**
- Ninja, git (macOS / Ubuntu에서 확인)

```bash
git clone https://github.com/Manggome/OneEmu.git
cd OneEmu

# 1) 코어 빌드 (처음엔 소스 클론 때문에 오래 걸립니다. -j 로 병렬 가능)
export ANDROID_HOME=~/Library/Android/sdk          # 또는 ANDROID_NDK_HOME
scripts/build-cores.sh            # 또는 scripts/build-cores.sh -j 3, 특정 코어만: scripts/build-cores.sh mgba
# 결과: app/src/main/jniLibs/arm64-v8a/lib<id>_libretro.so

# 2) 앱 빌드
./gradlew assembleDebug           # app/build/outputs/apk/debug/
./gradlew assembleRelease         # keystore.properties 가 있으면 그 키로, 없으면 debug 키로 서명
```

각 코어는 `cores/<id>/build.sh`(고정 커밋 클론 → 빌드 → strip → 복사)와 `cores/<id>/core.json`(메타데이터, BIOS 목록, 기본 옵션)으로 관리합니다. 자세한 규약은 [`cores/README.md`](cores/README.md)를 보세요.

### GitHub Actions

`.github/workflows/build.yml`이 `main`에 push될 때마다

1. 코어 6종을 각각 별도 job으로 빌드합니다. 결과 `.so`는 `cores/<id>/**` 해시로 캐시되어 코어 폴더가 바뀌지 않으면 다시 빌드하지 않습니다.
2. 코어를 모아 릴리스 APK를 서명·빌드하고 `OneEmu-v<버전>-arm64.apk`로 이름을 바꿉니다.
3. `v<버전>` 태그로 GitHub Release를 만들고 APK를 첨부합니다 (릴리스 노트 자동 생성).

`workflow_dispatch`로 수동 실행하면 APK를 Actions 아티팩트로만 올리고 릴리스는 만들지 않습니다.

## 릴리스 서명 설정

앱 내 업데이트가 덮어 설치되려면 모든 릴리스가 같은 키로 서명되어야 합니다.

```bash
scripts/make-keystore.sh          # keystore.jks 생성 + keystore.properties 작성 (둘 다 gitignored)
```

스크립트가 끝나면 아래 secrets를 저장소에 등록하는 `gh secret set` 명령을 그대로 출력합니다.

| Secret | 내용 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 < keystore.jks` |
| `KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `KEY_ALIAS` | 키 별칭 (기본 `oneemu`) |
| `KEY_PASSWORD` | 키 비밀번호 |

secrets가 없으면 워크플로는 CI 전용 임시 키를 만들어 서명합니다(Actions 캐시에 보관되어 캐시가 살아 있는 동안은 같은 키). 이 경우 키가 바뀔 때마다 사용자는 앱을 삭제하고 다시 설치해야 하므로, 배포용으로는 반드시 secrets를 설정하세요. `keystore.jks`는 잃어버리면 복구할 수 없으니 안전한 곳에 백업하세요.

## 라이선스

OneEmu 앱 코드는 **GPL-3.0**입니다 (GPL 코어를 함께 배포하기 때문입니다). 포함된 코어는 각자의 라이선스를 따르는 오픈 소스 프로젝트이며, 앱의 설정 → 정보에서 커밋과 저장소를 확인할 수 있습니다.

| 코어 | 라이선스 | 출처 |
| --- | --- | --- |
| mGBA | MPL-2.0 | https://github.com/libretro/mgba |
| FCEUmm | GPL-2.0 | https://github.com/libretro/libretro-fceumm |
| melonDS DS | GPL-3.0-or-later | https://github.com/JesseTG/melonds-ds |
| PPSSPP | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp |
| Play! | BSD-2-Clause | https://github.com/jpd002/Play- |
| MAME 2003-Plus | MAME 라이선스 (비상업적) | https://github.com/libretro/mame2003-plus-libretro |
| Azahar | GPL-2.0-or-later | https://github.com/azahar-emu/azahar |

OneEmu는 ROM, BIOS, 펌웨어를 포함하거나 배포하지 않습니다. 사용자가 합법적으로 소유한 파일만 사용해야 하며, 이에 대한 책임은 사용자에게 있습니다.
