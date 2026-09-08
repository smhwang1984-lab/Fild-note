# FieldNote Google Drive OAuth 설정

앱은 로컬 폴더를 Google Drive처럼 사용하지 않는다. Google Identity Services의
OAuth 승인 결과로 받은 단기 액세스 토큰을 사용해 로그인한 계정의 실제 Drive REST
API를 호출한다. 클라이언트 보안 비밀이나 `credentials.json`은 APK에 넣지 않는다.

## 1. Google Cloud 프로젝트 설정

1. Google Cloud Console에서 프로젝트를 만들거나 기존 프로젝트를 선택한다.
2. API 및 서비스에서 **Google Drive API**를 활성화한다.
3. Google Auth Platform의 Branding, Audience, Data Access를 설정한다.
4. Data Access에는 아래 범위를 선언한다.

   - `https://www.googleapis.com/auth/drive.file`
   - `openid`
   - `https://www.googleapis.com/auth/userinfo.email`

5. 외부 테스트 상태라면 앱을 사용할 Google 계정을 Test users에 등록한다.

`drive.file`은 앱이 만든 파일과 폴더만 다룰 수 있는 범위다. 앱은 사용자의 다른
Drive 문서를 읽거나 수정하지 않는다.

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

## 3. Drive 구조

최초 승인 및 동기화 때 다음 구조를 실제 Google Drive에 자동 생성한다.

```text
My Drive/
└── MyNoteApp/
    ├── Notes/
    └── Todos/
```

`Attachments/`는 첨부 기능이 처음 필요할 때만 생성된다. 생성된 폴더 ID는 기기에
캐시되며 계정을 변경하면 폐기된다.

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
- 로컬과 Drive가 마지막 동기화 이후 모두 변경되면 `revision`과 `updatedAt`으로
  충돌을 감지한다.
- 충돌 시 어느 쪽도 덮어쓰지 않고 로컬 `sync_conflicts` 테이블과 Logcat에 기록한다.

## 5. 실행 확인

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

앱의 설정 화면에서 **계정 연결**을 누르고 Drive 권한을 승인한다. 동기화 완료 후
Google Drive에서 `MyNoteApp/Notes`와 `MyNoteApp/Todos`를 확인한다. 같은 OAuth
프로젝트로 서명된 앱을 휴대폰과 태블릿에 설치하고 같은 Google 계정을 승인하면
양쪽이 동일한 파일을 기준으로 동기화한다.

OAuth 오류 `10 DEVELOPER_ERROR`가 발생하면 현재 설치한 APK의 package name과
SHA-1이 Google Cloud의 Android OAuth 클라이언트와 정확히 일치하는지 먼저 확인한다.
