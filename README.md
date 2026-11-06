# One UI Folder Organizer — experimental

삼성 **기본 One UI 앱스 화면**의 폴더 구성을 Home Up EXML 백업에서 편집하는 비공식 도구입니다. 별도 런처나 자체 앱서랍을 만들지 않습니다. Samsung/Good Lock 공식 제품이 아닙니다.

> **현재 상태:** 오프라인 EXML 변환기는 동작합니다. Android APK는 **파일 입출력 시험 앱**이며 폴더 편집 APK가 아닙니다. **Shizuku·ADB 없이 폰 하나에서 Home Up 백업을 가져오고 다시 넣는 경로는 시험 기기에서 막혔습니다.** 이 제한을 해결한 완성형 앱으로 소개하지 않습니다.

## 포함된 것

- `oneui_folders.py`: Python 표준 라이브러리만 사용하는 오프라인 변환기
- `examples/`: 실제 설치 앱이나 실제 백업이 아닌 **가상 EXML·분류안**
- `tests/`: 보호 영역, 앱 누락·중복, 페이지 계산, 입력·덮어쓰기 거부 검사
- `android/io-probe/`: 일반 앱 권한으로 파일 선택·사본 저장을 시험한 APK 소스
- [실기기 검증과 한계](docs/verification.md)

## 실제로 확인한 것

- Galaxy S26 Ultra / One UI 9 / Android 17 / Home Up 18.0.00.36의 실제 백업에서 EXML `appOrder` 부분을 편집하고 **Home Up에서 직접 복원**했습니다.
- 복원 후 새 백업을 내보내 앱 284개가 21개 순정 폴더에 누락·중복 없이 반영됐음을 대조하고, 앱스 화면에서도 폴더 이름을 확인했습니다.
- 해당 실행에서는 홈 배치·도크·위젯 4개의 위치와 호스트/제공자 연결이 유지됐습니다.
- 이 실기기 복원 과정의 파일 이동은 **PC ADB를 사용**했습니다. 일반 APK 단독으로 했다는 뜻이 아닙니다.
- 모든 삼성 기종/버전의 호환성이나 복원 안전성을 보증하지 않습니다.

## 가장 중요한 구분

**EXML 편집**과 **Home Up 백업 폴더에 파일을 주고받는 일**은 다릅니다.

1. 사용자가 Home Up에서 백업합니다.
2. 별도의 가능한 경로로 원본 파일을 가져옵니다.
3. 이 변환기가 앱스 영역만 편집한 새 EXML을 만듭니다.
4. 사용자가 파일을 Home Up 백업 폴더에 넣고 Home Up에서 복원합니다.

백업·복원 버튼을 직접 눌러도 Android의 다른 앱 데이터 디렉터리 접근 제한은 없어지지 않습니다. 시험 기기에서는 일반 파일 선택기에 `Android/data`가 표시되지 않았고, 삼성 내 파일도 해당 디렉터리를 열지 못했습니다.

- 일반 파일의 사용자 선택·저장: 권한 요청 없이 성공
- Home Up 백업 파일의 직접 읽기·쓰기: 일반 APK 권한으로는 미해결
- PC 파일 전송/ADB 등 별도 파일 이동 경로: 변환기와 별개
- Shizuku 또는 APK 내장 무선 ADB: 가능한 후속 방향이지만 이 저장소에는 구현하지 않음

공식 근거: [Android 문서 선택기의 접근 제한](https://developer.android.com/training/data-storage/shared/documents-files#access-restrictions), [모든 파일 접근 권한의 한계](https://developer.android.com/training/data-storage/manage-all-files#operations-allowed-manage-external-storage).

## 변환기 사용법

Python 3.9 이상이 필요합니다. 패키지 설치나 네트워크 요청은 없습니다.

가상 입력으로 먼저 실행하세요. **가상 샘플은 실제 휴대폰에 복원하지 마세요.**

```sh
python3 oneui_folders.py inspect examples/synthetic.exml
python3 oneui_folders.py build \
  --source examples/synthetic.exml \
  --plan examples/plan.json \
  --output build/demo.exml
python3 -m unittest discover -s tests -v
```

실제 사용 시에는 `--source`에 **방금 만든 개인 원본 백업**을, `--plan`에 로컬 분류안을 지정합니다. 패키지와 액티비티 문자열은 원본 그대로 써야 하며 활동 이름을 추측하거나 보정하지 않습니다. `inspect` 출력에는 개인 앱 목록이 들어가므로 공개 이슈에 붙이지 마세요.

분류안의 형태는 다음과 같습니다. 아래 식별자도 가상입니다.

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

현재 변환기는 원본 앱스의 모든 컴포넌트를 **이름 있는 폴더에 각각 정확히 한 번** 배정하는 방식입니다. 폴더당 최소 2개 앱이 필요합니다. 폴더 밖 단독 앱 유지, 숨긴 앱 처리, 자동 분류, APK 폴더 편집 UI는 아직 없습니다. 모르는 형식은 추측하지 않고 거부합니다.

## 안전장치와 복원 주의

- 원본 백업과 기존 출력 파일을 덮어쓰지 않습니다.
- 정확한 `appOrder` 바이트 구간만 교체합니다.
- 홈·도크·위젯·설정·DeX 등 **그 구간 밖 모든 바이트**가 원본과 동일한지 검사합니다.
- 앱 컴포넌트 누락·중복·추가, 숨긴 앱, 알 수 없는 항목/격자/정렬 형식을 거부합니다.
- 각 앱의 폴더 페이지를 제외한 메타데이터를 보존합니다.
- 출력 옆에 `.validation.json` 해시·검증 보고서를 만듭니다.

**파일에서 앱스 부분만 바꿔도, Home Up 복원 자체는 전체 레이아웃 복원입니다.** 위 검사는 파일 변환의 보존성을 증명할 뿐 삼성 런처의 복원 동작을 모든 버전에서 보장하지 않습니다. 원본을 보관하고, 복원 뒤 새 백업과 실제 홈·위젯을 확인하세요. 런처 데이터 삭제, 강제 DB 수정, 루팅은 이 도구의 사용법이 아닙니다.

## 시험 APK 빌드

JDK, Python 3, Android SDK의 `platforms;android-35`, `build-tools;36.0.0`이 필요합니다. Gradle이나 Shizuku 라이브러리는 사용하지 않습니다.

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk
python3 android/io-probe/build.py
```

결과: `android/io-probe/build/homeup-manual-io-probe.apk`

이 APK는 디버그 서명된 진단 시제품이며 요청 권한이 없습니다. 파일 선택기 열기, 가상 샘플 생성, 선택한 파일의 **동일한 사본** 저장·재읽기만 수행합니다. 런처 복원 기능은 없습니다. 로그와 선택 파일은 앱 내부에만 있고, 네트워크·텔레메트리는 없습니다. 실제 백업을 넣지 않고 가상 샘플만으로 시험할 수 있습니다.

## 개인정보·기여

실제 EXML, 앱 목록, 바로가기 URI/아이콘, 기기 주소, 계정 정보, 개인 스크린샷, 삼성 APK/역컴파일 코드는 업로드하지 마세요. 이 저장소에는 직접 작성한 코드와 가상 샘플만 포함합니다. 버그 보고는 버전 정보와 재현 가능한 **합성 입력**으로 부탁드립니다.

프로젝트 자체 코드는 MIT 라이선스입니다. Samsung 및 Good Lock의 이름·소프트웨어에 대한 권리를 주장하지 않습니다.
