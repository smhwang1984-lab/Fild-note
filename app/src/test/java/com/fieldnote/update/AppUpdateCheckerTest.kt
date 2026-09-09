package com.fieldnote.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun acceptsCanonicalAndRequestedTypoNamesCaseInsensitively() {
        assertEquals("1.2.7", AppUpdateChecker.parseVersionedApkName("FieldNote-v1.2.7.apk")?.versionName)
        assertEquals("1.2.8", AppUpdateChecker.parseVersionedApkName("FILDNOTE-V1.2.8.APK")?.versionName)
    }

    @Test
    fun rejectsUnversionedOrUnrelatedApkNames() {
        assertNull(AppUpdateChecker.parseVersionedApkName("update.apk"))
        assertNull(AppUpdateChecker.parseVersionedApkName("another-v1.2.7.apk"))
    }

    @Test
    fun comparesNumericVersionPartsInsteadOfAlphabetically() {
        assertTrue(AppUpdateChecker.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(AppUpdateChecker.compareVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, AppUpdateChecker.compareVersions("1.2.7", "1.2.7"))
    }
}
