package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the preference-reading code path in [WebhookConfigManager] —
 * `getWebhookConfig()`. The dialog-display code requires a real Activity and
 * AlertDialog and isn't unit-testable without a device; we skip it here and
 * rely on Espresso for that.
 */
@RunWith(RobolectricTestRunner::class)
class WebhookConfigManagerTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // Start each test with a clean slate
        getEncryptedSharedPreferences(appCtx).edit().clear().commit()
    }

    @Test fun `defaults come through when no webhook prefs are set`() {
        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("", cfg.url)
        assertEquals(appCtx.getString(R.string.webhook_get), cfg.method)
        assertEquals(appCtx.getString(R.string.webhook_location_do_not_include), cfg.includeLocation)
        assertEquals(10, cfg.timeout)
        assertEquals(0, cfg.retries)
        assertEquals(true, cfg.verifyCertificate)
        assertTrue(cfg.headers.isEmpty())
    }

    @Test fun `stored values round-trip through getWebhookConfig`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("webhook_url", "https://example.com/hook")
            .putString("webhook_method", appCtx.getString(R.string.webhook_post))
            .putString("webhook_include_location",
                appCtx.getString(R.string.webhook_location_body_json))
            .putInt("webhook_timeout", 45)
            .putInt("webhook_retries", 3)
            .putBoolean("webhook_verify_certificate", false)
            .putString("webhook_headers", """{"X-A":"1","X-B":"2"}""")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("https://example.com/hook", cfg.url)
        assertEquals(appCtx.getString(R.string.webhook_post), cfg.method)
        assertEquals(45, cfg.timeout)
        assertEquals(3, cfg.retries)
        assertEquals(false, cfg.verifyCertificate)
        assertEquals(mapOf("X-A" to "1", "X-B" to "2"), cfg.headers)
    }

    @Test fun `malformed header JSON is tolerated and falls back to empty headers`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("webhook_headers", "not-valid-json{{{")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertTrue("malformed header JSON shouldn't crash; headers must be empty",
            cfg.headers.isEmpty())
    }

    @Test fun `empty header JSON produces empty map`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("webhook_headers", "{}")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertTrue(cfg.headers.isEmpty())
    }

    @Test fun `missing webhook_url falls back to empty string`() {
        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()
        assertEquals("", cfg.url)
    }
}
