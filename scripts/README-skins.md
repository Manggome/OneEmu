# 온라인 패드 스킨 카탈로그 (`skins` 릴리스)

앱의 **설정 → 패드 레이아웃 → (기종) → 패드 스킨 → 온라인** 탭은 GitHub 릴리스 태그 `skins` 에 올라간
`catalog.json` 을 읽어 미리보기와 내려받기를 제공합니다. 카탈로그와 모든 파일은
`scripts/build-skin-catalog.py` 가 [libretro/common-overlays](https://github.com/libretro/common-overlays)
(`gamepads/`, **CC BY 4.0**) 에서 만들어 냅니다. 워크플로가 아니라 로컬에서 실행합니다.

## 다시 생성하기

```bash
# 요구 사항: python3, Pillow(pip install Pillow), git, gh(로그인 상태)
python3 scripts/build-skin-catalog.py --upload --prune
```

- 첫 실행이면 `build/skin-catalog/common-overlays` 에 저장소를 blobless + sparse(`gamepads/`만) 로 클론합니다
  (`--repo-dir` 로 위치 변경). 이미 있으면 그대로 쓰므로 최신으로 맞추려면 `git -C <repo-dir> pull` 을 먼저 하세요.
- 결과물은 `build/skin-catalog/dist/` (`--out`) 에 쌓이고, `--upload` 를 주면 `gh release upload --clobber` 로
  릴리스 `skins`(prerelease, "Pad skins catalog") 에 올립니다. 릴리스가 없으면 만듭니다. `catalog.json` 은 마지막에
  올려서 클라이언트가 아직 없는 파일을 가리키는 카탈로그를 읽지 않게 합니다.
- `--prune` 은 이번 실행에서 만들지 않은 릴리스 자산(이름이 바뀐 스킨 등)을 지웁니다.
- `--only flat/` 처럼 cfg 경로 부분 문자열로 일부만 만들어 볼 수 있습니다(디버깅용; 이 상태로 `--upload` 하지 마세요).
- 건너뛴 cfg 와 이유는 `build/skin-catalog/skipped.txt` 에 남습니다.

## 만들어지는 것

| 파일 | 내용 |
|---|---|
| `<id>.zip` | `<cfg>.cfg`(경로를 `img/` 로 다시 씀) + `skin.json` + `img/*.png`(1024px 초과는 축소). 앱의 `SkinStore.installZip` 이 `<externalFilesDir>/skins/<id>/` 에 풉니다 → 가져온 스킨(`user:<id>`)과 동일하게 동작 |
| `<id>-portrait.png`, `<id>-landscape.png` | 9:19.5 / 19.5:9 폰 캔버스(긴 쪽 480px) 미리보기. 앱과 같은 규칙(`OverlayCfg.pick`, `placeOverlay`)으로 배치. 한 방향만 지원하는 스킨은 "LANDSCAPE ONLY"/"PORTRAIT ONLY" 자리표시자 |
| `catalog.json` | 아래 스키마 |

```jsonc
{
  "schema": 1,
  "generatedAt": "2026-09-16T04:00:00Z",
  "source": "https://github.com/libretro/common-overlays",
  "license": "CC BY 4.0",
  "skins": [{
    "id": "flat-retropad",              // zip/폴더 이름, 앱 내부 id 는 "user:flat-retropad"
    "name": "RetroPad",
    "family": "flat",                   // gamepads/ 바로 아래 폴더 (Named_Overlays → "named", Piixel-Gamepads → "piixel")
    "author": "Sérgio Benjamim, hunterk", // git 이력에서 추출 (파일을 추가한 사람이 가장 큰 가중치, 저장소 전체 정리 커밋은 제외)
    "license": "CC BY 4.0",
    "systems": ["*"],                   // 추천 기종 id. "*" = 공용, [] = 특정 기종 없음(SNES/N64/제네시스 등 아직 코어 없는 기종)
    "source": "gamepads/flat/retropad.cfg",
    "previewPortrait": "https://github.com/Manggome/OneEmu/releases/download/skins/flat-retropad-portrait.png",
    "previewLandscape": "https://github.com/Manggome/OneEmu/releases/download/skins/flat-retropad-landscape.png",
    "zip": "https://github.com/Manggome/OneEmu/releases/download/skins/flat-retropad.zip",
    "size": 123456, "sha256": "…",
    "buttons": ["a","b","x","y","l","r","l2","r2","start","select","up","down","left","right"],
    "hasAnalog": true,
    "hasPortrait": true, "hasLandscape": true
  }]
}
```

## 원본에서 달라지는 점 (스크립트가 자동으로 처리)

- OneEmu 파서(`emu/skin/OverlaySkin.kt`)가 못 쓰는 cfg 는 제외: RetroPad 버튼이 하나도 없는 것, 메뉴/키보드 전용,
  `example`, `scummvm`, 참조 이미지가 없는 것, 버튼 4개 미만.
- 이미지 참조는 모두 `img/<파일명>` 으로 바꿔 zip 하나로 자급자족하게 합니다(`Named_Overlays` 는 `../flat/img/` 를 참조).
- `gamepads/old` 처럼 좌표를 배경 이미지 픽셀로 쓰는 구형 cfg 는 정규화 좌표로 바꾸고, 배경이 정사각형이 아니면
  `aspect_ratio` 를 배경 비율로 고정 + `full_screen=false` + `block_*_separation=true` 로 두어 앱이 늘리지 않고
  레터박스로 그리게 합니다.
- 같은 폴더의 `foo-landscape.cfg` + `foo-portrait.cfg`(또는 `foo.cfg` + `foo_portrait.cfg`)는 스킨 하나로 합칩니다
  (`skin.json` 의 `portraitCfg`/`landscapeCfg`).
- 각 zip 의 `skin.json` 에는 `modified` 항목으로 위 변경 내용을 적어 둡니다(CC BY 4.0 의 변경 고지).

## 앱 쪽 코드

- `emu/skin/SkinCatalog.kt` — 카탈로그 fetch(GitHub API → 직접 URL 폴백, 메모리/디스크 1시간 캐시), zip 내려받기 + sha256 검증, 설치/삭제 상태.
- `emu/skin/SkinStore.installZip` — 내려받은 zip 을 `skins/<id>/` 에 설치(가져오기와 같은 경로).
- `ui/skins/OnlineSkinsTab.kt` — 온라인 탭 UI. 문자열은 `res/values/strings_skins.xml`.
