package com.fieldnote

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldnote.core.BuildProfile
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.FieldNoteRepository
import com.fieldnote.data.LocalFieldNoteRepository
import com.fieldnote.data.LocalNoteStore
import com.fieldnote.data.NoteRecord
import com.fieldnote.data.sync.DriveFolderRegistry
import com.fieldnote.data.sync.DriveSessionStore
import com.fieldnote.data.sync.DriveSyncManager
import com.fieldnote.data.sync.GoogleDriveAuthorization
import com.fieldnote.data.sync.SyncDiagnostics
import com.fieldnote.data.sync.SyncDiagnosticsProvider
import com.fieldnote.data.sync.SyncPhase
import com.fieldnote.data.sync.SyncScheduler
import com.fieldnote.data.sync.SyncSnapshot
import com.fieldnote.data.sync.SyncStatusMonitor
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_DUE_DAY = 2

data class TodoItem(
    val id: Long,
    val title: String,
    val dueDay: Int? = null,
    val completed: Boolean = false
)

data class NoteTodoPlacement(
    val todoId: Long,
    val x: Float,
    val y: Float,
    val widthDp: Float = 220f,
    val heightDp: Float = 118f,
    val fixed: Boolean = false
)

data class NoteDocument(
    val id: String,
    val title: String,
    val content: String,
    val revision: Long,
    val updatedAt: Long
)

data class MainUiState(
    val versionName: String = BuildProfile.appVersion,
    val targetDevices: List<String> = BuildProfile.targetDevices
)

data class GoogleSyncState(
    val authorizing: Boolean = false,
    val accountName: String? = null,
    val syncEnabled: Boolean = false,
    val rootFolderName: String = DriveFolderRegistry.ROOT_NAME,
    val lastSyncedAt: String? = null,
    val phase: SyncPhase = SyncPhase.Idle,
    val pendingChanges: Int = 0,
    val conflicts: Int = 0,
    val message: String = "Google 계정을 연결하세요."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: FieldNoteRepository = LocalFieldNoteRepository()
    private val localStore = LocalNoteStore.get(application)
    private val session = DriveSessionStore(application)
    private val authorization = GoogleDriveAuthorization(application)
    private val syncManager = DriveSyncManager(application)

    val uiState = MainUiState()
    val featureStatuses: Flow<List<FeatureStatus>> = repository.featureStatuses

    var googleSyncState by mutableStateOf(loadGoogleSyncState())
        private set

    var currentNote by mutableStateOf(localStore.ensureSeedData().toDocument())
        private set

    val todos = mutableStateListOf<TodoItem>()
    val noteTodoPlacements = mutableStateListOf<NoteTodoPlacement>()

    init {
        reloadFromDatabase()
        viewModelScope.launch {
            SyncStatusMonitor.status.collect { snapshot ->
                applySyncSnapshot(snapshot)
                if (snapshot.phase == SyncPhase.Synced || snapshot.phase == SyncPhase.Conflict) {
                    reloadFromDatabase()
                }
            }
        }
        if (session.connected) {
            SyncScheduler.ensurePeriodic(application)
            SyncScheduler.enqueue(application)
        }
    }

    fun beginGoogleAuthorization(
        activity: Activity,
        launchResolution: (IntentSenderRequest) -> Unit
    ) {
        if (googleSyncState.authorizing) return
        googleSyncState = googleSyncState.copy(
            authorizing = true,
            phase = SyncPhase.Syncing,
            message = "Google Drive 접근을 요청하는 중..."
        )
        viewModelScope.launch {
            try {
                // 계정을 바꿀 때 이전 계정의 Drive 접근을 여기서 revoke하지 않는다.
                // drive.file 스코프는 파일 단위 권한이라 revoke하면 기존
                // MyNoteApp 폴더/파일에 대한 접근이 영구히 사라지고, 재승인해도
                // files.list가 빈 결과를 돌려줘 폴더가 중복 생성된다. 로컬 세션만
                // 비우고 재승인하면 DriveSyncManager.sync()가 계정 이메일을 비교해
                // 필요할 때만 폴더 캐시를 폐기한다.
                if (session.accountEmail != null) {
                    session.clear()
                }
                requestAuthorization(activity, launchResolution)
            } catch (error: TimeoutCancellationException) {
                authorizationFailed(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                authorizationFailed(error)
            }
        }
    }

    fun completeGoogleAuthorization(intent: Intent?) {
        try {
            finishAuthorization(authorization.tokenFromResult(intent))
        } catch (error: Exception) {
            authorizationFailed(error)
        }
    }

    // revokeAccess = false(기본값): 이 기기에서 로컬 연결 정보만 지운다. Drive에 만든
    // MyNoteApp 폴더의 권한은 그대로 남으므로 나중에 같은 계정으로 다시 연결하면
    // 기존 파일을 이어서 쓴다.
    // revokeAccess = true: Google 계정 자체에서 앱의 Drive 접근 권한을 취소한다.
    // drive.file은 파일 단위 권한이라 취소하면 MyNoteApp 폴더에 대한 접근이
    // 영구히 사라져서, 이후 재연결 시 새 폴더가 만들어진다. 사용자가 명시적으로
    // 요청했을 때만 호출해야 한다.
    fun disconnectGoogleAccount(activity: Activity, revokeAccess: Boolean = false) {
        if (googleSyncState.authorizing) return
        val email = session.accountEmail
        if (email == null || !revokeAccess) {
            clearDriveConnection()
            return
        }
        googleSyncState = googleSyncState.copy(authorizing = true)
        viewModelScope.launch {
            try {
                authorization.revoke(activity, email)
                clearDriveConnection()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                clearDriveConnection()
                googleSyncState = googleSyncState.copy(
                    phase = SyncPhase.Error,
                    message = "로컬 연결은 해제됐지만 Google 접근 권한 취소에는 실패했습니다."
                )
            }
        }
    }

    private fun clearDriveConnection() {
        session.clear()
        // 마커 기반 폴더 조회(DriveFolderRegistry/DriveRestClient)는 항상 기존
        // 폴더를 먼저 찾으므로 캐시를 비워도 중복 폴더가 생기지 않는다.
        DriveFolderRegistry(getApplication()).clear()
        SyncScheduler.cancel(getApplication())
        googleSyncState = GoogleSyncState(message = "Google Drive 연결이 해제됐습니다. 기기의 데이터는 그대로입니다.")
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = SyncPhase.AuthenticationRequired,
                message = "Google Drive 연결이 해제됐습니다. 기기의 데이터는 그대로입니다.",
                pendingChanges = localStore.pendingCount(),
                conflicts = localStore.conflictCount()
            )
        )
    }

    private suspend fun requestAuthorization(
        activity: Activity,
        launchResolution: (IntentSenderRequest) -> Unit
    ) {
        val result = authorization.authorize(activity)
        if (result.hasResolution()) {
            val pendingIntent = checkNotNull(result.pendingIntent) { "OAuth 처리 결과(pendingIntent)가 없습니다." }
            googleSyncState = googleSyncState.copy(message = "Google 계정 권한 승인을 기다리는 중...")
            launchResolution(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            finishAuthorization(checkNotNull(result.accessToken) { "OAuth 액세스 토큰이 없습니다." })
        }
    }

    fun syncNow() {
        if (googleSyncState.authorizing) return
        if (!session.connected) {
            googleSyncState = googleSyncState.copy(
                phase = SyncPhase.AuthenticationRequired,
                message = "먼저 Google 계정을 연결하세요."
            )
            return
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { authorization.silentToken() }
                if (token == null) {
                    googleSyncState = googleSyncState.copy(
                        phase = SyncPhase.AuthenticationRequired,
                        message = "Google 권한을 다시 승인해야 합니다."
                    )
                    return@launch
                }
                syncManager.sync(token, session.accountEmail)
                reloadFromDatabase()
            } catch (error: Exception) {
                syncFailed(error)
            }
        }
    }

    /** Snapshot for the settings-screen diagnostics panel: package name, signing SHA-1, cached
     * folder ids. Compare against the Android OAuth client registered in Google Cloud Console. */
    fun collectDiagnostics(): SyncDiagnostics = SyncDiagnosticsProvider.collect(getApplication())

    fun saveNoteContent(content: String) {
        currentNote = localStore.saveNote(currentNote.id, currentNote.title, content).toDocument()
        localChanged()
    }

    fun addTodo(title: String, dueDay: Int? = null) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val item = localStore.saveTodo(newTodoId(), trimmedTitle, dueDay, false)
        reloadTodos()
        addTodoToNote(item.id)
        localChanged()
    }

    fun addTodoToNote(todoId: Long) {
        if (noteTodoPlacements.any { it.todoId == todoId }) return
        noteTodoPlacements.add(
            NoteTodoPlacement(
                todoId = todoId,
                x = 120f,
                y = 220f + (noteTodoPlacements.size * 36f)
            )
        )
    }

    fun removeNoteTodo(todoId: Long) {
        val placement = noteTodoPlacements.firstOrNull { it.todoId == todoId } ?: return
        if (placement.fixed) return
        noteTodoPlacements.removeAll { it.todoId == todoId }
    }

    fun toggleTodo(todoId: Long) {
        updateTodo(todoId) { it.copy(completed = !it.completed) }
    }

    fun updateTodoDueDay(todoId: Long, dueDay: Int?) {
        updateTodo(todoId) { it.copy(dueDay = dueDay?.coerceIn(1, 30)) }
    }

    fun moveNoteTodo(todoId: Long, dx: Float, dy: Float) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index < 0 || noteTodoPlacements[index].fixed) return
        val current = noteTodoPlacements[index]
        noteTodoPlacements[index] = current.copy(
            x = (current.x + dx).coerceAtLeast(0f),
            y = (current.y + dy).coerceAtLeast(0f)
        )
    }

    fun resizeNoteTodo(todoId: Long, widthDeltaDp: Float, heightDeltaDp: Float) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index < 0 || noteTodoPlacements[index].fixed) return
        val current = noteTodoPlacements[index]
        noteTodoPlacements[index] = current.copy(
            widthDp = (current.widthDp + widthDeltaDp).coerceIn(160f, 420f),
            heightDp = (current.heightDp + heightDeltaDp).coerceIn(92f, 260f)
        )
    }

    fun toggleNoteTodoFixed(todoId: Long) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index >= 0) {
            noteTodoPlacements[index] = noteTodoPlacements[index].copy(
                fixed = !noteTodoPlacements[index].fixed
            )
        }
    }

    private fun finishAuthorization(accessToken: String) {
        googleSyncState = googleSyncState.copy(
            authorizing = true,
            message = "Google 접근 승인 완료. Drive에 연결하는 중..."
        )
        viewModelScope.launch {
            try {
                // DriveSyncManager.sync()가 이메일 확인 직후 session.connect(email)를
                // 호출하므로 여기서 다시 연결할 필요가 없다. sync가 예외 없이
                // 끝났다면 session.accountEmail은 항상 채워져 있다.
                syncManager.sync(accessToken)
                val email = session.accountEmail
                SyncScheduler.ensurePeriodic(getApplication())
                reloadFromDatabase()
                googleSyncState = googleSyncState.copy(
                    accountName = email,
                    syncEnabled = true,
                    message = "Google Drive에 연결됐습니다."
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                syncFailed(error)
            } finally {
                googleSyncState = googleSyncState.copy(authorizing = false)
            }
        }
    }

    private fun authorizationFailed(error: Throwable) {
        val message = when {
            error is TimeoutCancellationException ->
                "Google이 30초 안에 응답하지 않았습니다. 네트워크와 Google Play 서비스를 확인한 뒤 다시 시도하세요."
            error is ApiException && error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
                "Google OAuth 오류 10: 설정 화면의 진단 정보에 표시되는 패키지명·SHA-1이 " +
                    "Google Cloud의 Android OAuth 클라이언트 등록값과 일치하는지 확인하세요."
            error is ApiException && error.message?.contains("UNREGISTERED_ON_API_CONSOLE") == true ->
                "Google 인증 오류: 이 앱(패키지명·서명 SHA-1)에 해당하는 Android OAuth " +
                    "클라이언트가 Google Cloud Console에 아예 등록되어 있지 않습니다. " +
                    "설정 화면의 진단 정보에 표시되는 패키지명·SHA-1로 APIs & Services > " +
                    "Credentials에서 '만들기 > OAuth 클라이언트 ID > Android' 유형을 새로 " +
                    "등록하세요. 이미 등록했다면 Google Auth Platform의 Audience에서 " +
                    "게시 상태(테스트/프로덕션)와 테스트 사용자 등록 여부도 확인하세요."
            error is ApiException && error.statusCode == CommonStatusCodes.CANCELED ->
                "Google 로그인이 취소됐습니다. 다시 시도해 주세요."
            error is ApiException && error.statusCode == CommonStatusCodes.SIGN_IN_REQUIRED ->
                "Google 계정 로그인이 필요합니다. 기기의 Google 계정 상태를 확인하세요."
            error is ApiException && error.statusCode == CommonStatusCodes.NETWORK_ERROR ->
                "네트워크 오류로 Google 인증에 실패했습니다. 연결 상태를 확인하세요."
            error is ApiException ->
                "Google 인증 오류 ${error.statusCode}: ${error.message ?: CommonStatusCodes.getStatusCodeString(error.statusCode)}"
            else -> error.message ?: "Google 인증에 실패했습니다."
        }
        googleSyncState = googleSyncState.copy(
            authorizing = false,
            phase = SyncPhase.AuthenticationRequired,
            message = message
        )
    }

    private fun syncFailed(error: Throwable) {
        val snapshot = SyncSnapshot(
            phase = SyncPhase.Error,
            message = error.message ?: "Drive 동기화에 실패했습니다.",
            lastSyncedAt = session.lastSyncedAt,
            pendingChanges = localStore.pendingCount(),
            conflicts = localStore.conflictCount()
        )
        SyncStatusMonitor.update(snapshot)
        applySyncSnapshot(snapshot)
    }

    private fun updateTodo(todoId: Long, transform: (TodoItem) -> TodoItem) {
        val current = todos.firstOrNull { it.id == todoId } ?: return
        val updated = transform(current)
        localStore.saveTodo(updated.id, updated.title, updated.dueDay, updated.completed)
        reloadTodos()
        localChanged()
    }

    private fun localChanged() {
        val pending = localStore.pendingCount()
        googleSyncState = googleSyncState.copy(
            pendingChanges = pending,
            message = if (session.connected) "변경 사항이 Drive 업로드 대기 중입니다." else "이 기기에 오프라인으로 저장됐습니다."
        )
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = if (session.connected) SyncPhase.Idle else SyncPhase.Offline,
                message = googleSyncState.message,
                lastSyncedAt = session.lastSyncedAt,
                pendingChanges = pending,
                conflicts = localStore.conflictCount()
            )
        )
        if (session.connected) SyncScheduler.enqueue(getApplication())
    }

    private fun reloadFromDatabase() {
        currentNote = localStore.listNotes().firstOrNull()?.toDocument() ?: localStore.ensureSeedData().toDocument()
        reloadTodos()
    }

    private fun reloadTodos() {
        val records = localStore.listTodos()
        todos.clear()
        todos.addAll(records.map { TodoItem(it.id, it.title, it.dueDay, it.completed) })
        if (noteTodoPlacements.isEmpty()) {
            records.take(2).forEachIndexed { index, item ->
                noteTodoPlacements += NoteTodoPlacement(
                    todoId = item.id,
                    x = if (index == 0) 80f else 360f,
                    y = if (index == 0) 84f else 156f,
                    fixed = index == 1
                )
            }
        }
    }

    private fun newTodoId(): Long {
        var candidate: Long
        do {
            candidate = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        } while (candidate == 0L || localStore.findTodo(candidate) != null)
        return candidate
    }

    private fun loadGoogleSyncState(): GoogleSyncState = GoogleSyncState(
        accountName = session.accountEmail,
        syncEnabled = session.connected,
        lastSyncedAt = session.lastSyncedAt?.let(::formatTime),
        pendingChanges = localStore.pendingCount(),
        conflicts = localStore.conflictCount(),
        message = if (session.connected) "Google Drive 연결 정보를 복원했습니다." else "Google 계정을 연결하세요."
    )

    private fun applySyncSnapshot(snapshot: SyncSnapshot) {
        googleSyncState = googleSyncState.copy(
            accountName = session.accountEmail,
            syncEnabled = session.connected,
            lastSyncedAt = snapshot.lastSyncedAt?.let(::formatTime),
            phase = snapshot.phase,
            pendingChanges = snapshot.pendingChanges,
            conflicts = snapshot.conflicts,
            message = snapshot.message
        )
    }

    private fun NoteRecord.toDocument() = NoteDocument(id, title, content, revision, updatedAt)

    private fun formatTime(epochMillis: Long): String = FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    )

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
