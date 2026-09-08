package com.fieldnote.data.sync

import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleTaskTest {
    @Test
    fun returnsSuccessfulResult() = runTest {
        val source = TaskCompletionSource<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { source.task.awaitGoogleTask() }
        source.setResult("authorized")
        assertEquals("authorized", result.await())
    }

    @Test
    fun preservesFailureForDiagnostics() = runTest {
        val source = TaskCompletionSource<String>()
        val expected = IllegalStateException("OAuth configuration failure")
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { source.task.awaitGoogleTask() }
        }
        source.setException(expected)
        val actual = result.await().exceptionOrNull()
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    @Test
    fun canceledGoogleRequestFinishesWithRetryableError() = runTest {
        val cancellation = TestCancellationToken()
        val source = TaskCompletionSource<String>(cancellation)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { source.task.awaitGoogleTask() }
        }
        cancellation.cancel()
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun unresponsiveRequestTimesOutAndIgnoresLateSuccess() = runTest {
        val source = TaskCompletionSource<String>()
        val started = testScheduler.currentTime
        val result = runCatching { source.task.awaitGoogleTask() }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(30_000L, testScheduler.currentTime - started)
        source.setResult("late result")
    }

    @Test
    fun canceledCallerIgnoresLateFailure() = runTest {
        val source = TaskCompletionSource<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { source.task.awaitGoogleTask() }
        result.cancel()
        result.join()
        source.setException(IllegalStateException("late failure"))
        assertTrue(result.isCancelled)
    }

    // Deliver cancellation directly without an Android main looper in JVM tests.
    private class TestCancellationToken : CancellationToken() {
        private var canceled = false
        private lateinit var listener: OnTokenCanceledListener

        override fun isCancellationRequested() = canceled

        override fun onCanceledRequested(listener: OnTokenCanceledListener): CancellationToken {
            this.listener = listener
            return this
        }

        fun cancel() {
            canceled = true
            listener.onCanceled()
        }
    }
}
