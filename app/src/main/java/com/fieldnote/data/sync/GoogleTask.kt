package com.fieldnote.data.sync

import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private val directExecutor = Executor { it.run() }

// Limit service calls only; the user's consent screen has no deadline.
internal suspend fun <T> Task<T>.awaitGoogleTask(): T = withTimeout(30_000L) {
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(directExecutor) { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            when {
                task.isCanceled -> continuation.resumeWithException(
                    IllegalStateException("Google 인증 요청이 취소됐습니다. 다시 시도해 주세요.")
                )
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Google 인증에 실패했습니다.")
                )
            }
        }
    }
}
