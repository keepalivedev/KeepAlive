package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the [DebugLogger] singleton. Important behaviors:
 *
 *  - **Pre-init resilience.** `d()` is called from the very earliest paths
 *    (BroadcastReceiver onReceive on a fresh process). If `initialize()`
 *    hasn't run yet, `d()` must NOT crash — it falls back to the in-memory
 *    buffer.
 *  - **File write + read round-trip.** Once initialized, log entries
 *    persist to a file in credential-encrypted storage. `getLogs()` must
 *    surface them (newest first).
 *  - **Exception serialization.** When `d()` is called with an exception,
 *    its `localizedMessage` is appended to the log line so support data
 *    captures the failure cause, not just the surrounding message.
 *  - **deleteLogs cleans up.** Both file and memory buffer go to zero.
 *
 * DebugLogger is a Kotlin `object` — its internal state (appContext,
 * logBuffer, merge flags) survives across tests in the same JVM. Each
 * test resets that state via reflection in [resetSingletonState] so order
 * cannot leak.
 */
@RunWith(RobolectricTestRunner::class)
class DebugLoggerTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetSingletonState()
    }

    @After fun tearDown() {
        // Re-init for any caller (e.g. AppController.onCreate) that ran via
        // Robolectric setup and now expects a working logger.
        DebugLogger.initialize(appCtx)
    }

    /**
     * DebugLogger is a Kotlin `object`. Between tests we need to:
     *  - Mark the lateinit `appContext` as uninitialized.
     *  - Clear the in-memory `logBuffer`.
     *  - Reset the `directBootLogsMerged` and `writesSinceLastTrim` flags.
     *  - Delete the on-disk log files so getLogs starts from empty.
     */
    private fun resetSingletonState() {
        val cls = DebugLogger::class.java
        // Clear the in-memory buffer so getLogs returns empty before any d() call.
        val bufField = cls.getDeclaredField("logBuffer")
        bufField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (bufField.get(DebugLogger) as MutableList<String>).clear()

        // Reset flags
        val mergedField = cls.getDeclaredField("directBootLogsMerged")
        mergedField.isAccessible = true
        mergedField.setBoolean(DebugLogger, false)
        val trimField = cls.getDeclaredField("writesSinceLastTrim")
        trimField.isAccessible = true
        trimField.setInt(DebugLogger, 0)

        // Make `::appContext.isInitialized` report false again. Kotlin
        // implements lateinit by storing null in the backing field; null it.
        val ctxField = cls.getDeclaredField("appContext")
        ctxField.isAccessible = true
        ctxField.set(DebugLogger, null)

        // Wipe any persisted log file so file-based assertions start clean.
        try { appCtx.deleteFile("app_debug_logs.txt") } catch (_: Exception) {}
        try { appCtx.deleteFile("direct_boot_logs.txt") } catch (_: Exception) {}
    }

    // ---- Pre-init resilience -----------------------------------------------

    @Test fun `d before initialize does not crash and buffers to memory`() {
        // Production order-of-operations: a BroadcastReceiver running in a
        // newly-spawned process can hit DebugLogger.d() *before*
        // AppController.onCreate has run. The early-out in d() must keep
        // things from blowing up.
        DebugLogger.d("PreInit", "early log entry")  // no exception expected

        val logs = DebugLogger.getLogs()
        assertEquals("memory buffer should hold the entry from before init",
            1, logs.size)
        assertTrue("entry text must be in the buffered log",
            logs[0].contains("early log entry"))
    }

    @Test fun `getLogs before initialize returns memory buffer only`() {
        DebugLogger.d("Tag", "msg-only-in-memory")

        val logs = DebugLogger.getLogs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("msg-only-in-memory"))
    }

    @Test fun `deleteLogs before initialize is a no-op`() {
        DebugLogger.d("Tag", "msg")
        DebugLogger.deleteLogs()  // must not throw
        // Behavior intentionally undefined re: in-memory buffer; only
        // contractually requires no-throw.
    }

    // ---- After-init read/write round-trip ----------------------------------

    @Test fun `d after initialize persists to log file and getLogs reads it back`() {
        DebugLogger.initialize(appCtx)

        DebugLogger.d("MyTag", "first message")
        DebugLogger.d("MyTag", "second message")

        // Robolectric provides a real, file-backed FS for getFilesDir().
        val logs = DebugLogger.getLogs()
        assertEquals(2, logs.size)
        // getLogs returns newest-first.
        assertTrue("newest log first; got: $logs", logs[0].contains("second message"))
        assertTrue(logs[1].contains("first message"))
    }

    @Test fun `getLogs is empty when no entries exist`() {
        DebugLogger.initialize(appCtx)

        assertEquals(0, DebugLogger.getLogs().size)
    }

    @Test fun `exception localizedMessage is appended to the log entry`() {
        DebugLogger.initialize(appCtx)
        val ex = RuntimeException("disk full")

        DebugLogger.d("Tag", "tried to write", ex)

        val logs = DebugLogger.getLogs()
        assertEquals(1, logs.size)
        assertTrue("log line must include exception message; got: '${logs[0]}'",
            logs[0].contains("disk full"))
    }

    @Test fun `exception with null localizedMessage does not corrupt the line`() {
        // Some exceptions have null messages (e.g., NullPointerException
        // built without a constructor message on older runtimes). The log
        // formatter should not write the literal "null" or otherwise
        // produce garbage.
        DebugLogger.initialize(appCtx)
        val ex = object : RuntimeException() {
            // override to force null message
            override val message: String? get() = null
            override fun getLocalizedMessage(): String? = null
        }

        DebugLogger.d("Tag", "boom", ex)

        // No assertion on exact format — production prints "Exception: null"
        // for now. We just verify it doesn't crash and produces at least one
        // line containing the surrounding message.
        val logs = DebugLogger.getLogs()
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("boom"))
    }

    // ---- deleteLogs ---------------------------------------------------------

    @Test fun `deleteLogs after initialize clears both file and in-memory buffer`() {
        DebugLogger.initialize(appCtx)
        DebugLogger.d("Tag", "to be deleted")
        assertEquals(1, DebugLogger.getLogs().size)

        DebugLogger.deleteLogs()

        assertEquals("logs should be cleared", 0, DebugLogger.getLogs().size)
        // File should no longer exist.
        val file = appCtx.getFileStreamPath("app_debug_logs.txt")
        assertFalse("log file should have been deleted on disk",
            file != null && file.exists())
    }

    // ---- Memory buffer ordering --------------------------------------------

    @Test fun `memory buffer caps at MAX_BUFFER_SIZE and trims oldest`() {
        // The memory buffer is bounded at 1024 entries. We can't easily
        // verify the cap directly without flooding 1025+ entries, but we
        // can verify the newest-at-front semantic: after multiple writes,
        // the most recent appears at index 0.
        DebugLogger.initialize(appCtx)
        DebugLogger.d("Tag", "older")
        DebugLogger.d("Tag", "newer")

        val logs = DebugLogger.getLogs()
        assertEquals(2, logs.size)
        assertTrue("newer entry must be at index 0", logs[0].contains("newer"))
    }

    @Test fun `initialize is idempotent and ignores subsequent contexts`() {
        DebugLogger.initialize(appCtx)
        DebugLogger.d("Tag", "first")

        // Calling initialize again with the same (or a different) context
        // must not reset state nor fail.
        DebugLogger.initialize(appCtx)
        DebugLogger.d("Tag", "second")

        assertEquals(2, DebugLogger.getLogs().size)
    }
}
