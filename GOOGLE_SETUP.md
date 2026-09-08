# FieldNote Google Drive OAuth 설정

앱은 로컬 폴더를 Google Drive처럼 사용하지 않는다. Google Identity Services의
OAuth 승인 결과로 받은 단기 액세스 토큰을 사용해 로그인한 계정의 실제 Drive REST
API를 호출한다. 클라이언트 보안 비밀이나 `credentials.json`은 APK에 넣지 않는다.

## 1. Google Cloud 프로젝트 설정

1. Google Cloud Console에서 프로젝트를 만들거나 기존 프로젝트를 선택한다.
2. API 및 서비스에서 **Google Drive API**를 활성화한다.
3. Google Auth Platform의 Branding, Audience, Data Access를 설정한다.
4. Data Access에는 아래 범위 **하나만** 선언한다.

   - `https://www.googleapis.com/auth/drive.file`

   앱은 `drive/v3/about`을 이 범위만으로 호출해 로그인한 계정의 이메일도 함께
   얻으므로 `openid`나 `userinfo.email`은 등록할 필요가 없다. 스코프를 늘리면
   동의 화면 검수가 더 까다로워지고 승인이 실패할 여지도 늘어난다.

5. 외부 테스트 상태라면 앱을 사용할 Google 계정을 Test users에 등록한다.

`drive.file`은 앱이 만든 파일과 폴더만 다룰 수 있는 범위다. 앱은 사용자의 다른
Drive 문서를 읽거나 수정하지 않는다. 다만 이 범위는 **파일 단위 권한**이라서,
사용자가 Google 계정 설정에서 앱의 Drive 접근을 취소하면 앱이 만든
`MyNoteApp` 폴더에 대한 접근도 함께 영구히 사라진다. 그 뒤 다시 연결하면
기존 파일을 찾지 못하고 새 폴더가 만들어진다. 앱의 설정 화면에서도 "연결
해제"(이 기기의 로그인 정보만 지움, 기본값)와 "Google 액세스도 취소"(계정
자체에서 권한을 취소, 확인 대화상자 필요)를 분리해 두었다 — 여러 기기를 쓸
때는 후자를 함부로 누르면 안 된다.

## 2. Android OAuth 클라이언트

로컬 debug APK와 배포 release APK는 패키지명 또는 서명 인증서가 다르므로 Android
OAuth 클라이언트를 각각 만든다.

### Debug

- Package name: `com.fieldnote.debug`
- SHA-1: `0D:3D:39:6E:F0:44:5C:0A:0A:B0:1E:EB:B9:3A:50:E2:E1:FE:3A:1E`

### Release

- Package name: `com.fieldnote`
- SHA-1: `27:D0:54:B7:A8:0D:CC:3F:C8:C6:E7:A9:E6:6E:9C:5E:20:F6:CB:D5`

Google Play App Signing을 사용하게 되면 Play Console의 앱 서명 SHA-1로 OAuth
클라이언트를 하나 더 등록해야 한다.

**중요:** debug와 release는 서로 다른 OAuth 클라이언트이자 서로 다른 앱으로
취급된다. `drive.file` 범위는 파일 단위 권한이므로 **한 기기에는 release
APK, 다른 기기에는 debug APK**를 설치하면 같은 Google 계정으로 승인해도
서로 만든 파일이 보이지 않는다. 여러 기기 간 동기화를 확인하려면 반드시
**같은 빌드(예: 둘 다 release)** 를 설치한다.

### 설정값을 실제 설치본과 대조하기

앱 설정 화면 → **진단 정보** → "표시"를 누르면 현재 설치된 APK의 패키지명과
서명 SHA-1이 그대로 표시된다. 이 값이 위 표와 Google Cloud에 실제로 등록된
Android OAuth 클라이언트의 값과 정확히 같은지(대소문자, 콜론 위치 포함) 먼저
비교한다. 대부분의 "구성 에러"는 여기서 값이 어긋나 있는 경우다.

## 3. Drive 구조

최초 승인 및 동기화 때 다음 구조를 실제 Google Drive에 자동 생성한다.

```text
My Drive/
└── MyNoteApp/
    ├── Notes/
    └── Todos/
```

`Attachments/`는 첨부 기능이 처음 필요할 때만 생성된다.

폴더는 이름이 아니라 Drive의 `appProperties` 마커(`fieldnoteFolder =
root|notes|todos|attachments`)로 찾는다. 그래서 사용자가 Drive에서 폴더
이름을 바꾸거나 옮겨도, 또 다른 기기가 같은 계정으로 처음 연결해도 새 폴더를
만들지 않고 기존 폴더를 그대로 찾아 쓴다. 같은 마커의 폴더가 여러 개 있으면
(예: 두 기기가 동시에 최초 연결) 가장 먼저 만들어진 폴더로 수렴한다.

생성된 폴더 ID는 기기에 캐시되며, 사용할 때마다 Drive에 실제로 존재하는지
확인한다. 캐시된 폴더가 삭제·휴지통 이동됐거나 다른 계정의 것이면 자동으로
다시 찾거나 새로 만든다.

노트 파일은 `Notes/{UUID}.json`이며 다음 필드를 포함한다.

```json
{
  "id": "00000000-0000-4000-8000-000000000001",
  "title": "Field Note",
  "content": "serialized handwriting pages",
  "updatedAt": "2026-09-06T12:34:56.789Z",
  "revision": 3
}
```

## 4. 동기화 동작

- 노트와 할 일은 먼저 기기의 SQLite DB에 저장되므로 오프라인에서도 동작한다.
- 로컬 변경은 `dirty`로 표시되고 네트워크 연결 조건의 WorkManager 작업이 예약된다.
- 즉시 작업과 15분 주기 작업이 다른 기기의 변경도 내려받는다.
- 원격 목록은 비교에 사용하고, 실제 업로드/PATCH는 변경된 항목만 수행한다.
- 액세스 토큰은 약 1시간 뒤 만료된다. Drive 요청이 401을 받으면 자동으로
  조용히 토큰을 갱신해 한 번 더 시도하고, 429/5xx는 짧은 대기 후 최대 3회까지
  재시도한다.
- 로컬과 Drive가 마지막 동기화 이후 모두 변경되면 `revision`과 `updatedAt`으로
  충돌을 감지한다.
- 충돌 시 어느 쪽도 덮어쓰지 않고 로컬 `sync_conflicts` 테이블과 Logcat에 기록한다.
- "계정 변경"을 눌러도 이전 계정의 Drive 접근 권한은 취소하지 않는다(위 1절
  참고). 로컬 로그인 정보만 지우고 새 계정으로 다시 승인한다.

이번 범위에서 다루지 않는 것: 한 기기에서 지운 노트/할 일이 다른 기기에서
되살아나는 문제(삭제 전파, tombstone 미구현), `Attachments/` 폴더의 실제
사용(이미지·필기 첨부 업로드).

## 5. 실행 확인

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

앱의 설정 화면에서 **계정 연결**을 누르고 Drive 권한을 승인한다. 동기화 완료 후
Google Drive에서 `MyNoteApp/Notes`와 `MyNoteApp/Todos`를 확인한다. 같은 OAuth
프로젝트로 서명된(같은 빌드 변형의) 앱을 휴대폰과 태블릿에 설치하고 같은
Google 계정을 승인하면 양쪽이 동일한 파일을 기준으로 동기화한다.

## 6. 오류별 대처

| 상황 | 원인 | 대처 |
|---|---|---|
| OAuth 오류 10 (`DEVELOPER_ERROR`) | 설치된 APK의 패키지명·SHA-1이 Google Cloud의 Android OAuth 클라이언트와 **다름**(클라이언트는 존재) | 설정 화면의 진단 정보와 2절의 값을 대조. 새 클라이언트를 등록했다면 몇 분 정도 전파를 기다린다 |
| `[8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]` | 이 패키지명·SHA-1에 해당하는 Android OAuth 클라이언트가 Google Cloud Console에 **아예 없음** | 설정 화면 진단 정보의 패키지명·SHA-1로 APIs & Services > Credentials에서 "OAuth 클라이언트 ID 만들기 > Android"를 새로 등록. 등록했는데도 나면 Google Auth Platform > Audience의 게시 상태(테스트/프로덕션)와 테스트 사용자 등록 여부 확인 |
| 로그인 취소 / `CANCELED` | 사용자가 동의 화면을 닫음 | 다시 "계정 연결"을 누른다 |
| `SIGN_IN_REQUIRED` | 기기의 Google 계정 세션이 만료됨 | 기기 설정에서 Google 계정 로그인 상태 확인 |
| `NETWORK_ERROR` / 30초 타임아웃 | 네트워크 또는 Google Play 서비스 문제 | 연결 상태 확인 후 재시도 |
| 동기화 중 401 | 액세스 토큰 만료 | 앱이 자동으로 갱신 후 재시도한다. 계속 반복되면 재연결 필요 |
| 두 기기에서 서로 다른 내용이 보임 | 서로 다른 빌드 변형(debug/release)을 설치했거나 다른 계정으로 승인함 | 두 기기 모두 같은 빌드 변형 설치, 설정 화면에서 연결 계정 확인 |
