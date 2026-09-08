package com.fieldnote.data.sync

import android.app.Activity
import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

class GoogleDriveAuthorization(private val context: Context) {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES)
        .build()

    suspend fun authorize(activity: Activity): AuthorizationResult =
        Identity.getAuthorizationClient(activity).authorize(request)
            .awaitGoogleTask()

    fun tokenFromResult(intent: Intent?): String {
        checkNotNull(intent) { "Google sign-in was canceled or returned no result. Please try again." }
        return Identity.getAuthorizationClient(context)
            .getAuthorizationResultFromIntent(intent)
            .requireToken()
    }

    suspend fun revoke(
        activity: Activity,
        email: String
    ) {
        val revokeRequest = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(SCOPES)
            .build()
        Identity.getAuthorizationClient(activity).revokeAccess(revokeRequest)
            .awaitGoogleTask()
    }

    suspend fun silentToken(): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request).awaitGoogleTask()
        return if (result.hasResolution()) null else result.accessToken
    }

    private fun AuthorizationResult.requireToken(): String =
        accessToken ?: throw IllegalStateException("Google did not return an access token.")

    companion object {
        private val SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/drive.file"),
            Scope("openid"),
            Scope("https://www.googleapis.com/auth/userinfo.email")
        )
    }
}
