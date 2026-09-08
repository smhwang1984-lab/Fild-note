package com.fieldnote.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldnote.GoogleSyncState
import com.fieldnote.MainUiState
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.sync.SyncDiagnostics
import com.fieldnote.ui.navigation.ScreenFrame

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    featureStatuses: List<FeatureStatus>,
    googleSyncState: GoogleSyncState,
    onGoogleAuthorizationRequested: (Activity, (IntentSenderRequest) -> Unit) -> Unit,
    onGoogleAuthorizationCompleted: (android.content.Intent?) -> Unit,
    onGoogleAccountDisconnected: (Activity, Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onCollectDiagnostics: () -> SyncDiagnostics,
    tabletMode: Boolean
) {
    val context = LocalContext.current
    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onGoogleAuthorizationCompleted(result.data)
    }

    fun launchGoogleAuthorization() {
        val activity = context.findActivity() ?: return
        onGoogleAuthorizationRequested(activity, authorizationLauncher::launch)
    }

    fun disconnectGoogleAuthorization(revokeAccess: Boolean) {
        context.findActivity()?.let { onGoogleAccountDisconnected(it, revokeAccess) }
    }

    ScreenFrame(title = "설정", subtitle = "버전 및 동기화") {
        if (tabletMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                SettingsMenu(modifier = Modifier.width(220.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    VersionCard(uiState = uiState)
                    GoogleSyncCard(
                        state = googleSyncState,
                        onConnect = ::launchGoogleAuthorization,
                        onDisconnect = { disconnectGoogleAuthorization(false) },
                        onRevoke = { disconnectGoogleAuthorization(true) },
                        onSyncNow = onSyncNow
                    )
                    DiagnosticsCard(onCollectDiagnostics = onCollectDiagnostics)
                    FeatureStatusList(featureStatuses = featureStatuses)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsMenu(modifier = Modifier.fillMaxWidth())
                VersionCard(uiState = uiState)
                GoogleSyncCard(
                    state = googleSyncState,
                    onConnect = ::launchGoogleAuthorization,
                    onDisconnect = { disconnectGoogleAuthorization(false) },
                    onRevoke = { disconnectGoogleAuthorization(true) },
                    onSyncNow = onSyncNow
                )
                DiagnosticsCard(onCollectDiagnostics = onCollectDiagnostics)
                FeatureStatusList(featureStatuses = featureStatuses)
            }
        }
    }
}

@Composable
private fun SettingsMenu(modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
            Text(text = "설정 메뉴", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("버전") }
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Google 동기화") }
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("진행 상태") }
        }
    }
}

@Composable
private fun VersionCard(uiState: MainUiState) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(text = "현재 버전: ${uiState.versionName}", style = MaterialTheme.typography.titleMedium)
            Text(text = "최신 버전: 확인 전", style = MaterialTheme.typography.bodyMedium)
            Text(text = "대상 기기: ${uiState.targetDevices.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {}) { Text("새로고침") }
                Button(onClick = {}, enabled = false) { Text("업데이트") }
            }
        }
    }
}

@Composable
private fun GoogleSyncCard(
    state: GoogleSyncState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRevoke: () -> Unit,
    onSyncNow: () -> Unit
) {
    var showRevokeConfirm by remember { mutableStateOf(false) }

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Google 액세스를 취소할까요?") },
            text = {
                Text(
                    "이 기기의 연결만 해제하는 것이 아니라 Google 계정에서 앱의 Drive 접근 " +
                        "권한 자체를 취소합니다. 지금까지 만든 MyNoteApp 폴더에 대한 접근 " +
                        "권한도 함께 사라지므로, 나중에 다시 연결하면 새 폴더가 만들어집니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRevokeConfirm = false
                    onRevoke()
                }) { Text("액세스 취소") }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) { Text("취소") }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(text = "Google 계정 동기화", style = MaterialTheme.typography.titleMedium)
            Text(text = state.accountName?.let { "연결 계정: $it" } ?: "연결 계정: 없음", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Drive 루트: ${state.rootFolderName}", style = MaterialTheme.typography.bodySmall)
            Text(text = state.lastSyncedAt?.let { "마지막 동기화: $it" } ?: "마지막 동기화: 없음", style = MaterialTheme.typography.bodySmall)
            Text(text = "대기 ${state.pendingChanges}개 · 충돌 ${state.conflicts}개", style = MaterialTheme.typography.bodySmall)
            Text(text = state.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !state.authorizing) { Text(if (state.accountName == null) "계정 연결" else "계정 변경") }
                OutlinedButton(onClick = onSyncNow, enabled = state.accountName != null && !state.authorizing) { Text("동기화") }
                OutlinedButton(onClick = onDisconnect, enabled = state.accountName != null && !state.authorizing) { Text("연결 해제") }
            }
            if (state.accountName != null) {
                TextButton(onClick = { showRevokeConfirm = true }, enabled = !state.authorizing) {
                    Text("Google 액세스도 취소...", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(onCollectDiagnostics: () -> SyncDiagnostics) {
    var expanded by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<SyncDiagnostics?>(null) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = "진단 정보", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    diagnostics = onCollectDiagnostics()
                    expanded = !expanded
                }) { Text(if (expanded) "숨기기" else "표시") }
            }
            Text(
                text = "OAuth 오류 10(DEVELOPER_ERROR)이 나면 아래 값이 Google Cloud의 " +
                    "Android OAuth 클라이언트에 등록된 패키지명·SHA-1과 정확히 같은지 확인하세요.",
                style = MaterialTheme.typography.bodySmall
            )
            val info = diagnostics
            if (expanded && info != null) {
                Text(text = "패키지명: ${info.packageName}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "서명 SHA-1: ${info.signingSha1 ?: "확인 불가"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(text = "연결 계정: ${info.accountEmail ?: "없음"}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "루트/Notes/Todos 폴더 ID: " +
                        "${info.folders.rootId ?: "-"} / ${info.folders.notesId ?: "-"} / ${info.folders.todosId ?: "-"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "마지막 상태: ${info.lastPhase} - ${info.lastMessage ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun FeatureStatusList(featureStatuses: List<FeatureStatus>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(featureStatuses) { status -> FeatureStatusRow(status) }
    }
}

@Composable
private fun FeatureStatusRow(status: FeatureStatus) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = status.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = status.phase, style = MaterialTheme.typography.bodySmall)
            }
            Text(text = status.status, style = MaterialTheme.typography.labelLarge)
        }
    }
}
