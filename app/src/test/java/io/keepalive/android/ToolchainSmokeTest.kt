package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        // the fDroidLite flavor uses applicationId io.keepalive.lite.android and
        // debug builds append ".debug" — accept all flavor/build-type combinations.
        val pkg = ctx.packageName
        val expected = setOf(
            "io.keepalive.android",
            "io.keepalive.android.debug",
            "io.keepalive.lite.android",
            "io.keepalive.lite.android.debug",
        )
        assertTrue("unexpected applicationId: $pkg", pkg in expected)
    }

    @Test fun `mockk is on the classpath`() {
        val m = io.mockk.mockk<Runnable>(relaxed = true)
        m.run()
        io.mockk.verify { m.run() }
    }
}
