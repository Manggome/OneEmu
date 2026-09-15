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
| 아케이드 (2003~2010년 게임) | MAME 2010 | 실험적, MAME 0.139 롬셋 · **내려받기형 코어** |
| 아케이드 (최신 게임, ST-V 등) | MAME (최신) | 실험적, 최신 MAME 롬셋 · **내려받기형 코어** |
| 닌텐도 3DS | AzaharPlus | 실험적 (HW 렌더, OpenGL ES 3.2, 암호화 롬 지원) |

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
   - 아케이드 (MAME 2010): MAME 0.139 롬셋 `zip` (+ `chd` 는 `<게임명>/` 하위 폴더). 2003-Plus 에 없는 2003~2010년 추가 게임용
   - 3DS: `3ds`, `cci`, `cxi`, `app`, `3dsx`, `elf`, `axf`, `zcci`, `zcxi`, `z3dsx` (`cia` 설치는 지원하지 않음)

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
| 3DS | AzaharPlus | `Azahar/sysdata/aes_keys.txt`, `seeddb.bin`, `boot9.bin` | 선택 | 내장 키로 대부분의 암호화 롬이 열립니다. 열리지 않는 롬이 있을 때만 본체에서 추출한 키 파일을 넣으세요 |
| 아케이드 | MAME 2003-Plus | `mame2003-plus/cheat.dat`, `hiscore.dat`, `history.dat` | 선택 | 치트 / 하이스코어 / 히스토리 DB |
| 아케이드 | MAME 2010 | `mame2010/hiscore.dat`, `mame2010/cheat.zip`, `mame2010/samples/` | 선택 | 외부 하이스코어 DB / 치트 / 샘플. BIOS zip(neogeo.zip 등)은 게임 롬과 같은 폴더 |

- 3DS(AzaharPlus)는 업스트림 Azahar가 거부하는 **암호화된 롬**(.3ds/.cci/.cxi)도 내장 키로 복호화해 실행합니다. 키 파일은 기본적으로 필요 없고, 열리지 않는 롬이 있을 때만 3DS 본체에서 추출한 `aes_keys.txt`(GodMode9 `DumpKeys.gm9`)와 `seeddb.bin`을 `system/Azahar/sysdata/`에 넣습니다. **BIOS 파일 가져오기**로 `aes_keys.txt`를 선택하면 그 폴더에 자동으로 들어갑니다. .cia 설치는 지원하지 않습니다. 세이브는 `saves/3ds/Azahar/sdmc/` 아래 가상 SD에 저장됩니다.
- Android 11 이상에서는 일부 파일 관리자가 `Android/data`에 쓰지 못합니다. 그 경우 앱 안의 **BIOS 파일 가져오기**를 사용하세요.

## 선택형 코어(내려받기)

크기가 큰 코어는 APK에 넣지 않고 처음 필요할 때 GitHub에서 내려받습니다. 그래서 APK 크기가 작게 유지되고, 이 코어가 필요 없는 사용자는 저장공간을 쓰지 않습니다.

| 코어 | 용도 | 크기(대략) |
| --- | --- | --- |
| MAME 2010 | MAME 0.139 롬셋. 2003-Plus에 없는 2003~2010년 게임(남코 시스템 11/12, 철권, 블러디 로어 2 등) | 약 60 MB |
| MAME (최신) | 최신 MAME 롬셋. 옛 코어에서 미완성이거나 강제 종료되는 게임(세가 ST-V 등)과 0.139 이후 추가된 게임 | 수백 MB |

- **어디서 내려받나**: 게임을 실행할 때 필요한 코어가 없으면 "이 게임은 ○○ 코어가 필요합니다 (약 N MB). 지금 내려받을까요?" 대화상자가 뜨고, 내려받기가 끝나면 게임이 바로 시작됩니다. **설정 → 코어 / BIOS** 의 코어 카드에서도 내려받기 · 업데이트 · 삭제할 수 있고, 게임 상세의 롬 검사 카드에도 "실행 코어: … — 미설치, 내려받기" 가 표시됩니다.
- **저장 위치**: 앱 내부 저장공간 `cores/<코어 id>/` (예: `cores/mame2010/libmame2010_libretro.so` + `version.txt`). 앱을 삭제하면 함께 지워지며, 설정 화면의 **삭제**로 개별 삭제할 수 있습니다.
- **출처**: GitHub 릴리스의 고정 태그 [`cores`](https://github.com/Manggome/OneEmu/releases/tag/cores) 에 있는 `<코어 id>-<소스 커밋 12자리>-arm64.zip` 과 `manifest.json`. 앱은 manifest의 sha256으로 내려받은 파일을 검증합니다. 아직 `cores` 릴리스가 없거나 네트워크가 없으면 "아직 배포된 코어가 없습니다 / 코어 목록을 가져올 수 없습니다" 로 안내합니다.
- **자동 배정**: 아케이드 zip은 MAME 2003-Plus → MAME 2010 → MAME (최신) 순서로 게임 목록(DAT)에 있는 코어에 배정됩니다. 앞선 코어에서 미완성(preliminary)이거나 강제 종료가 알려진 기판(ST-V)은 뒤의 코어가 목록에 갖고 있으면 그쪽으로 배정되며, 그 코어가 설치되어 있지 않으면 위의 내려받기 대화상자가 뜹니다.
- 개발자용: 내려받기형 코어는 `cores/<id>/core.json` 의 `"distribution": "download"` 로 표시하고 `.github/workflows/cores.yml` 이 빌드·배포합니다 (`cores/README.md` 참고).

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

1. 코어 7종을 각각 별도 job으로 빌드합니다. 결과 `.so`는 `cores/<id>/**` 해시로 캐시되어 코어 폴더가 바뀌지 않으면 다시 빌드하지 않습니다.
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
| MAME 2010 | MAME 라이선스 (비상업적) | https://github.com/libretro/mame2010-libretro |
| AzaharPlus | GPL-2.0-or-later | https://github.com/AzaharPlus/AzaharPlus (Azahar 포크) |

OneEmu는 ROM, BIOS, 펌웨어를 포함하거나 배포하지 않습니다. 사용자가 합법적으로 소유한 파일만 사용해야 하며, 이에 대한 책임은 사용자에게 있습니다.
