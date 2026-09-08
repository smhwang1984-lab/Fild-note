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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldnote.GoogleSyncState
import com.fieldnote.MainUiState
import com.fieldnote.data.FeatureStatus
import com.fieldnote.ui.navigation.ScreenFrame

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    featureStatuses: List<FeatureStatus>,
    googleSyncState: GoogleSyncState,
    onGoogleAuthorizationRequested: (Activity, (IntentSenderRequest) -> Unit) -> Unit,
    onGoogleAuthorizationCompleted: (android.content.Intent?) -> Unit,
    onGoogleAccountDisconnected: (Activity) -> Unit,
    onSyncNow: () -> Unit,
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

    fun disconnectGoogleAuthorization() {
        context.findActivity()?.let(onGoogleAccountDisconnected)
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
                        onDisconnect = ::disconnectGoogleAuthorization,
                        onSyncNow = onSyncNow
                    )
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
                    onDisconnect = ::disconnectGoogleAuthorization,
                    onSyncNow = onSyncNow
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
    onSyncNow: () -> Unit
) {
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
