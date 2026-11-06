# One UI Folder Organizer — Shizuku preview

삼성 **기본 One UI 앱스 화면**의 폴더 구성을 Home Up EXML 백업에서 편집하는 비공식 도구입니다. 별도 런처나 자체 앱서랍을 만들지 않습니다. Samsung/Good Lock 공식 제품이 아닙니다.

> **v0.2 미리보기:** 앱이 **Shizuku 권한으로 Home Up 백업 폴더를 직접 읽고**, 폴더별 앱 배정을 편집하고, 검증한 새 백업 파일을 추가합니다. **파일관리자는 필요 없습니다.** 백업과 실제 복원은 Home Up에서 직접 합니다. Shizuku가 안 되면 [PC ADB 대안](docs/shizuku-and-adb.md)을 쓸 수 있습니다. 디버그 서명 시험판이며 모든 기종/버전의 호환성을 보증하지 않습니다.

## 폰에서 사용하는 순서

1. **Home Up → 백업과 복구 → 즉시**에서 새 원본을 만듭니다.
2. Shizuku를 실행하고 **One UI 폴더 정리 → Shizuku로 백업 불러오기**에서 권한을 허용합니다.
3. 최신 원본을 선택합니다. 현재 폴더와 앱 이름을 보여주며, 앱을 눌러 폴더를 바꿀 수 있습니다.
4. 폴더 필터·검색과 **폴더 관리**의 이름 변경·병합으로 구성합니다. 분류안은 앱 내부에 자동 저장됩니다.
5. **Home Up에 적용 준비 → 새 파일 준비**로 새 EXML을 추가합니다. 원본을 덮어쓰지 않습니다.
6. **Home Up 열기** 후 새 파일을 직접 선택해 복원합니다. 이 앱에는 런처 복원 기능이 없습니다.

[설정·사용법·ADB 명령·문제 해결](docs/shizuku-and-adb.md) · [검증한 것과 한계](docs/verification.md)

## 포함된 것

- `android/organizer/`: 오프라인 폴더 편집 UI + 선택적 Shizuku 파일 서비스
- `oneui_folders.py`: Python 표준 라이브러리만 사용하는 오프라인 변환기
- `tools/adb_backups.py`: 명시한 기기의 원본 읽기·수정본 준비용 PC ADB 대안
- `examples/`: 실제 설치 앱이나 실제 백업이 아닌 **가상 EXML·분류안**
- `tests/`, `android/organizer/tests/`: 보호 영역·앱 누락/중복·페이지·입력 거부 회귀 검사
- `android/organizer/device-tests/`: **별도·선택적** 실기기 통합 시험 APK. CI는 빌드만 하며 자동 설치/실행하지 않습니다.
- `android/io-probe/`: 이전 버전의 **권한 없는 IO 진단 앱**. 편집기 APK와 다릅니다.

## 실제 확인한 범위

Galaxy S26 Ultra / One UI 9 / Android 17 / Home Up 18.0.00.36 기준입니다.

- **기존 PC 경로:** EXML을 편집하고 Home Up에서 직접 복원한 뒤, 새 네이티브 백업과 실제 화면에서 **284개 앱·21개 순정 폴더**를 대조했습니다. 해당 실행에서 홈·도크·위젯 연결도 유지됐습니다.
- **새 Shizuku APK:** 승인된 파일 서비스의 **shell UID 2000**과 Home Up 폴더 직접 읽기를 확인했습니다. 현재 284개 앱·21개 폴더를 앱으로 가져왔습니다.
- **실기기 통합 시험:** 실제 Android의 앱 이동/필터 처리, 분류안 저장·재불러오기, Shizuku Binder 읽기·새 파일 저장·해시 검증, 잘못된 경로·원본 변경·보호 영역 변경·덮어쓰기 거부를 검사했습니다. 임시 앱 이동은 되돌립니다. 상세 증거 범위는 [검증 문서](docs/verification.md)에 구분했습니다.
- **PC ADB 대안:** 실제 기기에서 목록·원본 읽기·새 파일 준비·동일 이름 거부를 실행했습니다.
- 새 Shizuku 버전 시험에서는 **런처 복원을 누르지 않았습니다.** 기존 PC 경로의 복원 성공을 새 APK의 복원 성공으로 소개하지 않습니다.

일반 앱 권한의 파일 선택기만으로는 시험 기기의 `Android/data`에 접근하지 못했습니다. `MANAGE_EXTERNAL_STORAGE`나 파일관리자 설치를 해결책으로 요구하지 않습니다. Shizuku 역시 삼성 signature 권한을 주는 것이 아니라, **승인된 shell 수준 파일 서비스**만 제공합니다.

## PC 오프라인 변환기

Python 3.9 이상. 변환 자체에는 패키지 설치·네트워크·ADB·Shizuku가 필요 없습니다.

```sh
python3 oneui_folders.py inspect examples/synthetic.exml
python3 oneui_folders.py build \
  --source examples/synthetic.exml \
  --plan examples/plan.json \
  --output build/demo.exml
python3 -m unittest discover -s tests -v
```

**가상 샘플을 실제 휴대폰에 복원하지 마세요.** 실제 사용에는 방금 만든 개인 원본과 로컬 분류안을 지정합니다. 패키지와 액티비티 문자열은 원본 그대로 써야 하며 추측·보정하지 않습니다. `inspect` 출력과 분류안 JSON에는 개인 앱 목록이 들어가므로 공개 이슈에 붙이지 마세요.

분류안의 형태는 다음과 같습니다. 식별자도 가상입니다.

```json
{
  "folders": [
    {
      "name": "원하는 폴더",
      "apps": [
        {"package": "example.fixture.alpha", "activity": "example.fixture.Alpha"},
        {"package": "example.fixture.beta", "activity": "example.fixture.Beta"}
      ]
    }
  ]
}
```

앱의 분류안 JSON에는 여기에 `version`과 `source_sha256`도 기록하며, 다른 원본의 분류안은 거부합니다.

현재 출력은 원본 앱스의 모든 컴포넌트를 **이름 있는 폴더에 각각 정확히 한 번** 배정합니다. 폴더당 최소 2개 앱이 필요합니다. 폴더 밖 단독 앱 유지, 숨긴 앱, 아이콘 편집은 아직 지원하지 않습니다. **미분류만 추천**은 설치 앱의 Android 카테고리를 쓰는 단순 추천이며, 알 수 없는 앱은 기타로 둡니다. AI가 앱 목록을 외부로 보내 분류하지 않습니다. 이미 정리한 폴더는 이 추천으로 바꾸지 않습니다.

## 안전장치와 복원 주의

- 원본 백업과 기존 출력 파일을 덮어쓰지 않습니다. 기기 파일 쓰기는 새 이름만 허용합니다.
- 정확한 `appOrder` 바이트 구간만 교체합니다.
- 홈·도크·위젯·설정·DeX 등 **그 구간 밖 모든 바이트**의 원본 동일성을 검사합니다.
- 앱 컴포넌트 누락·중복·추가, 숨긴 앱, 알 수 없는 항목/격자/정렬 형식, DTD/entity를 거부합니다. 입력은 최대 8 MiB입니다.
- 폴더 페이지 외 앱 메타데이터를 보존하고, 기기 원본·전송 파일·저장 후 해시를 확인합니다.
- 존재하는 동반 파일만 새 이름으로 복사합니다. 원본 동반 파일을 수정하거나 필요성을 추측해 생성하지 않습니다.
- Shizuku 파일 서비스는 고정 Home Up 경로만 다루며, 임의 명령·일반 파일관리·런처 DB API를 제공하지 않습니다. 루트 모드는 지원하지 않습니다.

**파일에서 앱스 부분만 바꿔도 Home Up 복원은 전체 레이아웃 복원입니다.** 위 검사는 파일 변환의 보존성을 증명할 뿐 모든 버전의 삼성 런처 동작을 보장하지 않습니다. 원본을 보관하고 복원 뒤 새 백업과 실제 홈·위젯을 확인하세요. 런처 데이터 삭제·DB 직접 수정·루팅은 이 도구의 사용법이 아닙니다.

## APK 빌드

JDK 21, Python 3, Android SDK의 `platforms;android-35`와 `build-tools;36.0.0`이 필요합니다. Gradle은 사용하지 않습니다. **빌드 시에만** Maven Central에서 해시 고정된 Shizuku API 13.1.5(MIT) 라이브러리를 받습니다.

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
python3 android/organizer/build.py
```

결과: `android/organizer/build/oneui-folder-organizer.apk`

최소 Android 11. 요청 권한은 Shizuku API 하나이며 인터넷·전체 파일 접근·삼성 특권을 요청하지 않습니다. 원본·분류안·진단 결과는 앱 내부 저장소에만 저장합니다. APK는 디버그 서명 시제품이며 배포용 안정 버전이 아닙니다. 로컬 빌드마다 키가 다르면 기존 앱 위에 업데이트할 수 없으니 개인 분류안을 먼저 내보내세요.

이전 IO 진단 APK는 `python3 android/io-probe/build.py`로 별도 빌드합니다. 요청 권한 없이 파일 선택·동일 사본 저장만 시험하는 도구이며 Shizuku 편집기와 혼동하지 마세요.

## 개인정보·기여

실제 EXML, 앱 목록, 바로가기 URI/아이콘, 기기 주소, 계정 정보, 개인 스크린샷, 삼성 APK/역컴파일 코드는 업로드하지 마세요. 저장소에는 직접 작성한 코드와 가상 샘플만 포함합니다. 버그 보고는 버전 정보와 재현 가능한 **합성 입력**으로 부탁드립니다.

프로젝트 자체 코드는 MIT입니다. [Shizuku API 제3자 고지](android/organizer/THIRD_PARTY_NOTICES.md). Samsung 및 Good Lock의 이름·소프트웨어에 대한 권리를 주장하지 않습니다.
