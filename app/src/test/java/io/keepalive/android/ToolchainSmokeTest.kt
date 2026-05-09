package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test: proves Robolectric 4.16 boots against compileSdk 36 / AGP 9 /
 * Kotlin 2.3. If this file stops compiling or this test stops passing, do
 * not bother writing any of the other test files until it's fixed.
 */
@RunWith(RobolectricTestRunner::class)
class ToolchainSmokeTest {

    @Test fun `robolectric application context is available`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ctx)
        // debug builds append ".debug" to the applicationId; accept either.
        val pkg = ctx.packageName
        assertEquals(true, pkg == "io.keepalive.android" || pkg == "io.keepalive.android.debug")
    }

    @Test fun `mockk is on the classpath`() {
        val m = io.mockk.mockk<Runnable>(relaxed = true)
        m.run()
        io.mockk.verify { m.run() }
    }
}
