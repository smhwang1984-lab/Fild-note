package com.fieldnote.ui.screen

import android.net.Uri
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldnote.MainUiState
import com.fieldnote.SyncUiState
import com.fieldnote.UpdateUiState
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.sync.ConflictPolicy
import com.fieldnote.ui.navigation.ScreenFrame

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    featureStatuses: List<FeatureStatus>,
    syncState: SyncUiState,
    updateState: UpdateUiState,
    onSyncFolderPicked: (Uri) -> Unit,
    onSyncFolderDisconnected: () -> Unit,
    onSyncNow: () -> Unit,
    onRefreshUpdate: () -> Unit,
    onClearConflicts: () -> Unit,
    onConflictPolicyChange: (ConflictPolicy) -> Unit,
    onInstallUpdate: () -> Unit,
    tabletMode: Boolean
) {
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onSyncFolderPicked) }

    ScreenFrame(title = "설정", subtitle = "버전 및 동기화") {
        if (tabletMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                SettingsMenu(modifier = Modifier.width(220.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    VersionCard(uiState = uiState, updateState = updateState, onRefresh = onRefreshUpdate, onInstallUpdate = onInstallUpdate)
                    SyncFolderCard(
                        state = syncState,
                        onPickFolder = { folderPickerLauncher.launch(null) },
                        onDisconnect = onSyncFolderDisconnected,
                        onSyncNow = onSyncNow,
                        onClearConflicts = onClearConflicts,
                        onConflictPolicyChange = onConflictPolicyChange
                    )
                    FeatureStatusList(featureStatuses = featureStatuses)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsMenu(modifier = Modifier.fillMaxWidth())
                VersionCard(uiState = uiState, updateState = updateState, onRefresh = onRefreshUpdate, onInstallUpdate = onInstallUpdate)
                SyncFolderCard(
                    state = syncState,
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onDisconnect = onSyncFolderDisconnected,
                    onSyncNow = onSyncNow,
                    onClearConflicts = onClearConflicts,
                    onConflictPolicyChange = onConflictPolicyChange
                )
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
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("동기화 폴더") }
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("진행 상태") }
        }
    }
}

@Composable
private fun VersionCard(
    uiState: MainUiState,
    updateState: UpdateUiState,
    onRefresh: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(text = "현재 버전: ${uiState.versionName}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = updateState.message ?: "동기화 폴더 루트에 FieldNote-vX.Y.Z.apk 형식의 파일을 두면 자동으로 상위 버전을 확인합니다. SHA 파일은 선택 사항입니다.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(text = "대상 기기: ${uiState.targetDevices.joinToString()}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !updateState.checking) {
                    Text(if (updateState.checking) "확인 중" else "새로고침")
                }
                Button(onClick = onInstallUpdate, enabled = updateState.availableVersionName != null) { Text("업데이트") }
            }
        }
    }
}

@Composable
private fun SyncFolderCard(
    state: SyncUiState,
    onPickFolder: () -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onClearConflicts: () -> Unit,
    onConflictPolicyChange: (ConflictPolicy) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(text = "동기화 폴더", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "시스템 파일 선택창에서 Google Drive(또는 다른 클라우드 앱) 안의 폴더를 " +
                    "고르세요. 다른 기기에서도 같은 폴더를 선택하면 내용이 동기화됩니다.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(text = state.folderName?.let { "선택한 폴더: $it" } ?: "선택한 폴더: 없음", style = MaterialTheme.typography.bodyMedium)
            Text(text = state.lastSyncedAt?.let { "마지막 동기화: $it" } ?: "마지막 동기화: 없음", style = MaterialTheme.typography.bodySmall)
            Text(text = "대기 ${state.pendingChanges}개 · 충돌 ${state.conflicts}개", style = MaterialTheme.typography.bodySmall)
            Text(text = state.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFolder, enabled = !state.syncing) {
                    Text(if (state.folderName == null) "폴더 선택" else "폴더 변경")
                }
                OutlinedButton(onClick = onSyncNow, enabled = state.folderName != null && !state.syncing) { Text("동기화") }
                OutlinedButton(onClick = onDisconnect, enabled = state.folderName != null && !state.syncing) { Text("연결 해제") }
            }
            Text(
                text = "충돌 시 우선(둘 다 수정된 경우에만 적용, 최신순은 수정 시각 기준)",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConflictPolicyChip("최신순", state.conflictPolicy == ConflictPolicy.Newest) { onConflictPolicyChange(ConflictPolicy.Newest) }
                ConflictPolicyChip("이 기기 우선", state.conflictPolicy == ConflictPolicy.Local) { onConflictPolicyChange(ConflictPolicy.Local) }
                ConflictPolicyChip("Drive 우선", state.conflictPolicy == ConflictPolicy.Remote) { onConflictPolicyChange(ConflictPolicy.Remote) }
            }
            if (state.conflicts > 0) {
                OutlinedButton(onClick = onClearConflicts) { Text("충돌 기록 지우기") }
            }
        }
    }
}

@Composable
private fun ConflictPolicyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(if (selected) "$label*" else label) })
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
