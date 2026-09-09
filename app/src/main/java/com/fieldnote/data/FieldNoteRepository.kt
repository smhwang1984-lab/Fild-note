package com.fieldnote.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class FeatureStatus(
    val name: String,
    val phase: String,
    val status: String
)

interface FieldNoteRepository {
    val featureStatuses: Flow<List<FeatureStatus>>
}

class LocalFieldNoteRepository : FieldNoteRepository {
    override val featureStatuses = MutableStateFlow(
        listOf(
            FeatureStatus("기본 화면", "1단계", "완료"),
            FeatureStatus("S펜 필기", "2단계", "완료"),
            FeatureStatus("제스처 확대/축소", "2단계", "완료"),
            FeatureStatus("스트로크 안정화", "2단계", "완료"),
            FeatureStatus("페이지 추가", "2단계", "완료"),
            FeatureStatus("노트 위 할 일 배치", "3단계", "완료"),
            FeatureStatus("할 일 크기 조정", "3단계", "완료"),
            FeatureStatus("할 일 수동 제거", "3단계", "완료"),
            FeatureStatus("할 일 목록", "4단계", "완료"),
            FeatureStatus("월간 달력", "5단계", "완료"),
            FeatureStatus("Google OAuth 계정 연결", "6단계", "완료"),
            FeatureStatus("Drive 앱 루트 자동 생성", "6단계", "완료"),
            FeatureStatus("Google Calendar 원격 동기화", "7단계", "OAuth 설정 필요"),
            FeatureStatus("Drive 증분 양방향 동기화", "8단계", "OAuth 설정 필요"),
            FeatureStatus("APK 업데이트", "9단계", "Drive 폴더 필요")
        )
    )
}
