package io.keepalive.android

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Tests the preference-reading code path in [WebhookConfigManager] —
 * `getWebhookConfig()` — and what the dialog does with the include location
 * setting. The rest of the dialog is covered by the instrumented tests.
 */
@RunWith(RobolectricTestRunner::class)
class WebhookConfigManagerTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    // the setting as older versions saved it: the label on screen, in whatever language
    //  the app was in. literals so that a changed translation can't change what is tested
    private val savedLabels = mapOf(
        "do_not_include" to listOf(
            "Do Not Include", "Nicht einschließen", "No incluir", "Ne pas inclure",
            "Non includere", "Nie Dołączaj", "Не включать", "不包含"
        ),
        "body_json" to listOf(
            "Body - JSON", "Körper - JSON", "Cuerpo - JSON", "Corps - JSON",
            "Corpo - JSON", "Treść - JSON", "Тело - JSON", "请求正文 - JSON"
        ),
        "body_form" to listOf(
            "Body - Form", "Körper - Formular", "Cuerpo - Formulario", "Corps - Formulaire",
            "Corpo - Formulario", "Treść - Formularz", "Тело - Форма", "请求正文 - 表单"
        ),
        "query_parameters" to listOf(
            "Query Parameters", "Abfrageparameter", "Parámetros de consulta",
            "Paramètres de requête", "Parametri di query", "Parametry Zapytania",
            "Параметры запроса", "查询参数"
        )
    )

    private fun saveIncludeLocation(
        value: String,
        locationEnabled: Boolean,
        prefs: SharedPreferences = getAppSharedPreferences(appCtx)
    ) {
        prefs.edit()
            .putString("webhook_url", "https://example.com/hook")
            .putString("webhook_method", "POST")
            .putString("webhook_include_location", value)
            .putBoolean("webhook_location_enabled", locationEnabled)
            .commit()
    }

    private fun savedIncludeLocation() =
        getAppSharedPreferences(appCtx).getString("webhook_include_location", null)

    private fun savedLocationEnabled() =
        getAppSharedPreferences(appCtx).getBoolean("webhook_location_enabled", false)

    @Before fun setUp() {
        // Start each test with a clean slate
        getAppSharedPreferences(appCtx).edit().clear().commit()
        getDeviceProtectedPreferences(appCtx).edit().clear().commit()
    }

    @Test fun `defaults come through when no webhook prefs are set`() {
        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("", cfg.url)
        assertEquals(appCtx.getString(R.string.webhook_get), cfg.method)
        assertEquals(WebhookLocationOption.DO_NOT_INCLUDE, cfg.includeLocation)
        assertEquals(10, cfg.timeout)
        assertEquals(0, cfg.retries)
        assertEquals(true, cfg.verifyCertificate)
        assertTrue(cfg.headers.isEmpty())
    }

    @Test fun `stored values round-trip through getWebhookConfig`() {
        getAppSharedPreferences(appCtx).edit()
            .putString("webhook_url", "https://example.com/hook")
            .putString("webhook_method", appCtx.getString(R.string.webhook_post))
            .putString("webhook_include_location", "body_json")
            .putInt("webhook_timeout", 45)
            .putInt("webhook_retries", 3)
            .putBoolean("webhook_verify_certificate", false)
            .putString("webhook_headers", """{"X-A":"1","X-B":"2"}""")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("https://example.com/hook", cfg.url)
        assertEquals(appCtx.getString(R.string.webhook_post), cfg.method)
        assertEquals(WebhookLocationOption.BODY_JSON, cfg.includeLocation)
        assertEquals(45, cfg.timeout)
        assertEquals(3, cfg.retries)
        assertEquals(false, cfg.verifyCertificate)
        assertEquals(mapOf("X-A" to "1", "X-B" to "2"), cfg.headers)
    }

    @Test fun `malformed header JSON is tolerated and falls back to empty headers`() {
        getAppSharedPreferences(appCtx).edit()
            .putString("webhook_headers", "not-valid-json{{{")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertTrue("malformed header JSON shouldn't crash; headers must be empty",
            cfg.headers.isEmpty())
    }

    @Test fun `empty header JSON produces empty map`() {
        getAppSharedPreferences(appCtx).edit()
            .putString("webhook_headers", "{}")
            .commit()

        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertTrue(cfg.headers.isEmpty())
    }

    @Test fun `missing webhook_url falls back to empty string`() {
        val cfg = WebhookConfigManager(appCtx, null).getWebhookConfig()
        assertEquals("", cfg.url)
    }

    // ---- migrating saved labels ---------------------------------------------

    @Test fun `a label saved in any language becomes its fixed value`() {
        savedLabels.forEach { (fixedValue, labels) ->
            labels.forEach { label ->
                saveIncludeLocation(label, locationEnabled = true)

                WebhookConfigManager(appCtx, null).getWebhookConfig()

                assertEquals(label, fixedValue, savedIncludeLocation())
            }
        }
    }

    @Test fun `do not include from any language turns the location flag off`() {
        // the flag can be on here: re-saving the dialog after a language change compared the
        //  old label against the current 'do not include', which never matched
        savedLabels.getValue("do_not_include").forEach { label ->
            saveIncludeLocation(label, locationEnabled = true)

            WebhookConfigManager(appCtx, null).getWebhookConfig()

            assertFalse(label, savedLocationEnabled())
        }
    }

    @Test fun `a location format from any language turns the location flag on`() {
        (savedLabels - "do_not_include").values.flatten().forEach { label ->
            saveIncludeLocation(label, locationEnabled = false)

            WebhookConfigManager(appCtx, null).getWebhookConfig()

            assertTrue(label, savedLocationEnabled())
        }
    }

    @Test fun `an unrecognised saved value turns location off`() {
        saveIncludeLocation("Body - XML", locationEnabled = true)

        WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("do_not_include", savedIncludeLocation())
        assertFalse(savedLocationEnabled())
    }

    @Test fun `no saved value leaves the prefs untouched`() {
        WebhookConfigManager(appCtx, null).getWebhookConfig()

        val prefs = getAppSharedPreferences(appCtx)
        assertFalse(prefs.contains("webhook_include_location"))
        assertFalse(prefs.contains("webhook_location_enabled"))
    }

    @Test fun `startup migrates so the flag is right before anything reads it`() {
        // AlertService reads webhook_location_enabled before it loads the webhook config
        saveIncludeLocation("Nicht einschließen", locationEnabled = true)

        (appCtx as AppController).onCreate()

        assertFalse(savedLocationEnabled())
    }

    @Test fun `the device-protected copy is migrated along with the main prefs`() {
        // an alert before the first unlock reads this copy
        val devicePrefs = getDeviceProtectedPreferences(appCtx)
        saveIncludeLocation("Körper - JSON", locationEnabled = true)
        saveIncludeLocation("Körper - JSON", locationEnabled = true, prefs = devicePrefs)

        WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("body_json", devicePrefs.getString("webhook_include_location", null))
    }

    @Test fun `a label is migrated while the device is locked`() {
        val userManager = appCtx.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(userManager).setUserUnlocked(false)
        val devicePrefs = getDeviceProtectedPreferences(appCtx)
        saveIncludeLocation("Nicht einschließen", locationEnabled = true, prefs = devicePrefs)

        WebhookConfigManager(appCtx, null).getWebhookConfig()

        assertEquals("do_not_include", devicePrefs.getString("webhook_include_location", null))
        assertFalse(devicePrefs.getBoolean("webhook_location_enabled", true))
    }

    // ---- the dialog ---------------------------------------------------------

    private fun openDialog(): AlertDialog {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        WebhookConfigManager(activity, activity).showWebhookConfigDialog {}
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.includeLocationField(): AutoCompleteTextView =
        findViewById(R.id.spinnerIncludeLocation)

    // what AutoCompleteTextView does when the user picks an item from the dropdown
    private fun AutoCompleteTextView.choose(position: Int) {
        setText(adapter.getItem(position) as String, false)
        onItemClickListener.onItemClick(null, null, position, 0)
    }

    private fun AlertDialog.pressSave() {
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `the dialog shows the translated label for the saved value`() {
        saveIncludeLocation("body_form", locationEnabled = true)

        val field = openDialog().includeLocationField()

        assertEquals("Körper - Formular", field.text.toString())
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `a body format saved in another language still locks the method to POST`() {
        saveIncludeLocation("Body - JSON", locationEnabled = true)

        val dialog = openDialog()

        assertFalse(dialog.findViewById<AutoCompleteTextView>(R.id.spinnerHttpMethod).isEnabled)
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `choosing an option saves the fixed value and not the label`() {
        saveIncludeLocation("do_not_include", locationEnabled = false)
        val dialog = openDialog()

        dialog.includeLocationField().choose(3)
        dialog.pressSave()

        assertEquals("query_parameters", savedIncludeLocation())
        assertTrue(savedLocationEnabled())
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `choosing do not include turns the location flag off`() {
        saveIncludeLocation("body_json", locationEnabled = true)
        val dialog = openDialog()

        dialog.includeLocationField().choose(0)
        dialog.pressSave()

        assertEquals("do_not_include", savedIncludeLocation())
        assertFalse(savedLocationEnabled())
    }

    @Test @Config(qualifiers = "de-rDE")
    fun `re-saving after a language change keeps do not include off`() {
        saveIncludeLocation("Do Not Include", locationEnabled = false)

        openDialog().pressSave()

        assertEquals("do_not_include", savedIncludeLocation())
        assertFalse(savedLocationEnabled())
    }
}
