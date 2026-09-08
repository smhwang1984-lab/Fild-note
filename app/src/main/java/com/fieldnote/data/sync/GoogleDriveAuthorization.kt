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
        checkNotNull(intent) { "Google 로그인이 취소되었거나 결과가 없습니다. 다시 시도해 주세요." }
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
        accessToken ?: throw IllegalStateException("Google이 액세스 토큰을 반환하지 않았습니다.")

    companion object {
        // drive.file 하나만 요청한다. 이 범위는 앱이 만든 파일/폴더에만 적용되며
        // drive/v3/about 호출로 사용자 이메일도 함께 얻을 수 있어 openid나
        // userinfo.email 스코프가 필요 없다. 스코프를 줄이면 동의 화면 구성이
        // 단순해지고 승인 실패 가능성도 줄어든다.
        private val SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/drive.file")
        )
    }
}
