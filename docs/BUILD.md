# 개발 문서

OneEmu 를 직접 빌드하거나 코어를 추가할 때 보는 문서입니다. 앱을 쓰기만 할 거라면
[README](../README.md) 만 보셔도 됩니다.

## 직접 빌드하기

필요한 것

- JDK 17
- Android SDK: `platforms;android-37` (compileSdk 37, minSdk 26), `build-tools;35.0.0`,
  `cmake;3.22.1`, **NDK 28.2.13676358**
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

1. APK 에 들어가는 코어를 각각 별도 job 으로 빌드합니다 (매트릭스는 `build.yml` 의 `core:` 목록). 결과 `.so`는 `cores/<id>/**` 해시로 캐시되어 코어 폴더가 바뀌지 않으면 다시 빌드하지 않습니다.
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

## 코어 추가하기

코어 하나는 폴더 하나입니다.

```
cores/<id>/
  build.sh    고정 커밋 클론 -> 빌드 -> strip -> app/src/main/jniLibs/arm64-v8a/ 로 복사
  core.json   메타데이터: 담당 기종, 확장자, BIOS 목록, 기본 옵션, 라이선스, 소스 커밋
```

자세한 규약은 [`cores/README.md`](../cores/README.md) 에 있습니다. 코어를 추가하거나
바꾼 뒤에는 라이선스 목록을 다시 만들어 주세요.

```bash
python3 scripts/gen-third-party.py
```

