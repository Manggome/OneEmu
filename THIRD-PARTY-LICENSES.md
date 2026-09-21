<!-- scripts/gen-third-party.py 가 만듭니다. 직접 고치지 말고 스크립트를 다시 돌리세요. -->
# 포함된 오픈 소스 (Third-party licenses)

OneEmu 는 여러 오픈 소스 에뮬레이터 코어를 함께 배포합니다. 각 코어는 원래 프로젝트의
라이선스를 그대로 따릅니다.

## ⚠ 상업적 이용 제한

아래 코어는 **상업적 이용을 금지**합니다. 이 코어가 들어 있는 한 OneEmu 를
**판매하거나 유료 앱·광고 수익 목적으로 배포할 수 없습니다.** 무료 배포는 허용됩니다.

- **Genesis Plus GX (메가드라이브 / 마스터 시스템)** (`genesisplusgx`, APK 에 포함) — Non-commercial
- **MAME 2003-Plus** (`mame2003plus`, APK 에 포함) — MAME (non-commercial)
- **MAME 2010** (`mame2010`, 내려받기형) — MAME (non-commercial)

상업적으로 배포하려면 이 코어들을 빼거나 상업적 이용이 가능한 코어로 바꿔야 합니다.

## 앱 코드

OneEmu 자체 코드는 **GPL-3.0-or-later** 입니다. GPL 코어와 함께 배포되므로 전체가
GPL 조건을 따릅니다. 전문은 [`LICENSE`](LICENSE) 에 있습니다.

## 코어

| 코어 | 담당 기종 | 라이선스 | 배포 | 출처 |
| --- | --- | --- | --- | --- |
| ARMSX2 (플레이스테이션 2) | ps2 | GPL-3.0-or-later | 내려받기 | [ARMSX2/ARMSX2](https://github.com/ARMSX2/ARMSX2) `f4272b6768a2` |
| AzaharPlus (3DS) | 3ds | GPL-2.0-or-later | APK 포함 | [AzaharPlus/AzaharPlus](https://github.com/AzaharPlus/AzaharPlus) `263745c1df2c` |
| Dolphin (게임큐브/Wii) | gc | GPL-2.0-or-later | 내려받기 | [libretro/dolphin](https://github.com/libretro/dolphin) `ed70219e8bf8` |
| FCEUmm | nes | GPL-2.0 | APK 포함 | [libretro/libretro-fceumm](https://github.com/libretro/libretro-fceumm) `236ccdfc911e` |
| Genesis Plus GX (메가드라이브 / 마스터 시스템) | md, sms, gg | Non-commercial ⚠ | APK 포함 | [libretro/Genesis-Plus-GX](https://github.com/libretro/Genesis-Plus-GX) `c2838c7dc423` |
| 재즈 잭래빗 2 (Jazz² Resurrection) | jazz2 | GPL-3.0-or-later | APK 포함 | [deathkiller/jazz2-native](https://github.com/deathkiller/jazz2-native) `9cc77a769ca0` |
| MAME (최신) | arcade | GPL-2.0-or-later (MAME) | 내려받기 | [libretro/mame](https://github.com/libretro/mame) `4fc9a9312baa` |
| MAME 2003-Plus | arcade | MAME (non-commercial) ⚠ | APK 포함 | [libretro/mame2003-plus-libretro](https://github.com/libretro/mame2003-plus-libretro) `d3ac6c95f293` |
| MAME 2010 | arcade | MAME (non-commercial) ⚠ | 내려받기 | [libretro/mame2010-libretro](https://github.com/libretro/mame2010-libretro) `dff8aadd1c3f` |
| melonDS DS | nds | GPL-3.0-or-later | APK 포함 | [JesseTG/melonds-ds](https://github.com/JesseTG/melonds-ds) `bc4e4b67d2d4` |
| mGBA | gba, gbc, gb | MPL-2.0 | APK 포함 | [libretro/mgba](https://github.com/libretro/mgba) `e31759b24e7a` |
| PCSX-ReARMed | psx | GPL-2.0 | APK 포함 | [libretro/pcsx_rearmed](https://github.com/libretro/pcsx_rearmed) `8625c395a244` |
| Play! | ps2 | BSD-2-Clause | APK 포함 | [jpd002/Play-](https://github.com/jpd002/Play-) `83700b2c31e5` |
| PPSSPP | psp | GPL-2.0-or-later | APK 포함 | [hrydgard/ppsspp](https://github.com/hrydgard/ppsspp) `fa50bb197606` |

## 그 밖의 포함물

- **패드 스킨** — [libretro/common-overlays](https://github.com/libretro/common-overlays), CC BY 4.0.
  APK 에 들어간 것은 `app/src/main/assets/skins/CREDITS.md` 에 표기했습니다.
- **Dolphin 런타임 파일** (`cores/dolphin/assets/`) — Dolphin 이 자기 저장소에서 공개 배포하는
  파일만 복사했습니다. 폰트는 Droid Sans 기반으로 Dolphin 팀이 직접 만든 것이며
  (Apache-2.0, `GC/font-licenses.txt` 에 전문 포함), 닌텐도의 파일은 들어 있지 않습니다.

## 포함되지 않은 것

OneEmu 는 **게임 ROM, BIOS, 펌웨어, 암호화 키를 포함하거나 배포하지 않습니다.**
이 저장소에도, 배포하는 APK 에도 들어 있지 않습니다. 사용자가 합법적으로 소유한
기기와 게임에서 직접 추출한 파일만 사용해야 합니다.
