package com.fieldnote.core

import com.fieldnote.BuildConfig

object BuildProfile {
    // Was previously hardcoded ("1.0.4") and silently drifted from the real
    // app/build.gradle.kts versionName on every release. Read it from BuildConfig so the
    // Settings screen always reflects what's actually installed.
    val appVersion: String = BuildConfig.VERSION_NAME
    val targetDevices = listOf(
        "Galaxy Z Fold3 펼침 화면",
        "Galaxy Tab S7 태블릿 화면"
    )
}
