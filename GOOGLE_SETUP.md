# Field Note Google/Drive Setup

현재 로컬 앱 정보:

- Application ID: `com.fieldnote`
- Version name: `1.0.4`
- Version code: `5`
- Release certificate SHA-1: `27:D0:54:B7:A8:0D:CC:3F:C8:C6:E7:A9:E6:6E:9C:5E:20:F6:CB:D5`
- Release certificate SHA-256: `57:D4:19:E3:8A:23:7A:B2:98:49:9D:B8:58:05:0E:06:61:26:8E:2D:B5:F8:BD:2F:BC:2A:76:B0:A6:5F:1E:C7`

v1.0.4 현재 구현:

- Android 시스템 계정 선택 화면으로 기기 내 Google 계정 선택
- 선택 계정명 로컬 저장
- 계정별 로컬 동기화 준비 폴더 자동 생성
- 설정 화면에서 계정 변경, 연결 해제, 폴더 자동 설정, 동기화 실행 상태 표시
- 앱 재시작 후 선택 계정과 저장 폴더 복원

Phase 7-9 원격 동기화 진행에 필요한 외부 설정:

1. Google Cloud Console에서 Android OAuth Client 생성
2. Package name에 `com.fieldnote` 등록
3. 위 release SHA-1 등록
4. Web OAuth Client 생성 또는 기존 Web Client ID 제공
5. Google Calendar API 활성화
6. Google Drive API 활성화
7. 앱 전용 Drive 폴더 또는 지정 폴더 ID 확정
8. Calendar/Drive OAuth scope와 동의 화면 게시 상태 확정
9. APK 업데이트용 `version.json` 형식 확정

APK 업데이트용 기본 JSON 후보:

```json
{
  "versionName": "1.0.5",
  "versionCode": 6,
  "apkFileName": "FieldNote-v1.0.5-release.apk",
  "sha256": "APK_SHA256_VALUE"
}
```

주의:

- 이후 업데이트 APK는 반드시 `keystore/fieldnote-v1.jks`와 같은 키로 서명해야 설치 업데이트가 가능하다.
- 현재 `keystore.properties`는 로컬 개발 편의를 위한 평문 설정 파일이므로 외부 공유/커밋 대상에서 제외해야 한다.
