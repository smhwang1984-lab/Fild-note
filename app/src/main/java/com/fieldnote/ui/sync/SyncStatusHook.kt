package com.fieldnote.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.fieldnote.data.sync.SyncSnapshot
import com.fieldnote.data.sync.SyncStatusMonitor

@Composable
fun rememberSyncStatus(): State<SyncSnapshot> =
    SyncStatusMonitor.status.collectAsState()
