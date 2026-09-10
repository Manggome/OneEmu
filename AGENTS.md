# OneEmu 개발 규약 (사람/에이전트 공통)

## 구조
- `app/src/main/cpp/` — C++ libretro 프론트엔드 (`Frontend`, `VideoGL`, `AudioOutput`, JNI `NativeBridge`)
- `app/src/main/java/com/manggome/oneemu/`
  - `model/SystemId.kt` — 기종 enum (한글 이름, 색, 확장자)
  - `core/` — `CoreInfo`(core.json), `CoreRegistry`(APK 내 코어 목록, .so 경로, BIOS)
  - `data/db/` — Room: `GameEntity`, `FolderEntity`, `CheatEntity` + DAO
  - `data/Settings.kt` — DataStore 래퍼. 키는 `Settings.Keys`
  - `library/` — `RomScanner`(폴더 스캔/기종 판별), `RomInfo`(헤더 제목, NDS 아이콘)
  - `emu/` — `NativeBridge`(JNI), `EmulatorSession`(게임 1개 세션), `EmulatorActivity`
  - `ui/` — Compose 화면. `ui/theme/Theme.kt`, `ui/Routes.kt`, `ui/MainActivity.kt`(NavHost)
  - `util/` — `AppDirs`(앱 폴더 구조), `StorageAccess`(권한, SAF→경로)
  - `update/` — GitHub Releases 기반 앱 내 업데이트
- `cores/<id>/` — 코어별 build.sh, core.json (규약: `cores/README.md`)
- 의존성 주입: `OneEmuApp.get()` 에서 `settings`, `db`, `cores`, `dirs`, `scanner`

## 규칙
- **모든 사용자 노출 문자열은 한글**, 기능별 `res/values/strings_<feature>.xml` 에 둔다. `strings.xml` 은 공통 문자열 전용.
- Compose + Material3, 테마는 `OneEmuTheme`/`OneEmuColors` 사용. 다크 톤(#1E1E1E/#2B2B2B) + 민트(#7FD1C8) 포인트.
- 기능 패키지는 자기 패키지 안에서만 파일을 만든다. 공용 파일(`Settings.kt`, `Entities.kt`, `Routes.kt`, `Theme.kt`, `MainActivity.kt`, `NativeBridge.kt`, `EmulatorSession.kt`, cpp)은 담당자만 수정.
  - 새 설정 값이 필요하면 자기 패키지에 `stringPreferencesKey(...)` 등을 선언하고 `Settings.observe/get/set` 제네릭 API를 쓴다.
- 네비게이션: 각 기능은 `fun NavGraphBuilder.<feature>Graph(nav: NavHostController)` 확장 함수를 노출하고, `MainActivity.AppNavHost` 연결은 통합 담당이 한다.
- 파일 경로는 `java.io.File` 절대 경로. SAF URI는 `StorageAccess.treeUriToPath` 로 변환한 뒤에만 저장.
- BIOS/ROM 을 저장소에 넣지 않는다. 회사 이메일 등 개인 정보를 코드/문서에 넣지 않는다.
- 빌드 확인: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug`
