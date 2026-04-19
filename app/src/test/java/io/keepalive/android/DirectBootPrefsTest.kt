package io.keepalive.android

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Direct Boot storage-switching behavior. This is the subtle bit where
 * [getEncryptedSharedPreferences] falls back to device-protected storage
 * when the user hasn't unlocked yet — and those are two SEPARATE backing
 * stores, not the same prefs file.
 *
 * A bug here is invisible in normal use but catastrophic on reboot (data
 * written in one store, expected in the other).
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootPrefsTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val userManager = appCtx.getSystemService(Context.USER_SERVICE) as UserManager
    private val shadowUser get() = shadowOf(userManager)

    @Before fun setUp() {
        shadowUser.setUserUnlocked(true)
        // Start clean; these tests write to both stores independently.
        getEncryptedSharedPreferences(appCtx).edit().clear().commit()
        getDeviceProtectedPreferences(appCtx).edit().clear().commit()
    }

    // ---- isUserUnlocked -----------------------------------------------------

    @Test fun `isUserUnlocked reflects the UserManager state - unlocked`() {
        shadowUser.setUserUnlocked(true)
        assertTrue(isUserUnlocked(appCtx))
    }

    @Test fun `isUserUnlocked reflects the UserManager state - locked`() {
        shadowUser.setUserUnlocked(false)
        assertEquals(false, isUserUnlocked(appCtx))
    }

    // ---- getEncryptedSharedPreferences fallback -----------------------------

    @Test fun `when unlocked, returns credential-encrypted prefs (a different instance from device-protected)`() {
        shadowUser.setUserUnlocked(true)

        val enc = getEncryptedSharedPreferences(appCtx)
        val dev = getDeviceProtectedPreferences(appCtx)

        assertNotNull(enc)
        assertNotNull(dev)
        assertNotSame("credential-encrypted and device-protected prefs must be separate instances",
            enc, dev)
    }

    @Test fun `when locked, getEncryptedSharedPreferences falls back to device-protected`() {
        shadowUser.setUserUnlocked(false)

        val falllback = getEncryptedSharedPreferences(appCtx)
        val dev = getDeviceProtectedPreferences(appCtx)

        // Robolectric caches SharedPreferences by name, so two calls to the
        // same-named file return the same instance. Use a write-through-read
        // test to prove they share the same backing.
        falllback.edit().putString("marker", "X").commit()
        assertEquals("locked-mode reads must go to device-protected storage",
            "X", dev.getString("marker", null))
    }

    @Test fun `value written when unlocked is visible via the unlocked-path read`() {
        shadowUser.setUserUnlocked(true)
        getEncryptedSharedPreferences(appCtx).edit().putString("k", "v").commit()

        val roundtrip = getEncryptedSharedPreferences(appCtx).getString("k", null)

        assertEquals("v", roundtrip)
    }

    @Test fun `value written while locked is visible to the unlocked-path fallback only if also synced`() {
        // Key invariant: data written while locked goes to device-protected.
        // It is NOT automatically visible via the credential-encrypted store
        // after unlock. This test documents that behavior — the app must
        // explicitly sync prefs (via syncPrefsToDeviceProtectedStorage) in
        // the other direction for credential-encrypted state to be seen
        // during subsequent Direct Boot.
        shadowUser.setUserUnlocked(false)
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("directBootWrite", "yes").commit()

        shadowUser.setUserUnlocked(true)
        val cred = getEncryptedSharedPreferences(appCtx).getString("directBootWrite", null)
        val dev = getDeviceProtectedPreferences(appCtx).getString("directBootWrite", null)

        assertEquals("device-protected still has it", "yes", dev)
        // Value is NOT in credential-encrypted because the fallback wrote to
        // device-protected, not credential-encrypted.
        assertEquals("credential-encrypted does NOT auto-see the Direct Boot write",
            null, cred)
    }

    // ---- syncPrefsToDeviceProtectedStorage ----------------------------------

    @Test fun `sync copies credential-encrypted values into device-protected storage`() {
        shadowUser.setUserUnlocked(true)
        val defaultPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appCtx)
        defaultPrefs.edit()
            .putString("synced_string", "hello")
            .putBoolean("synced_bool", true)
            .putInt("synced_int", 42)
            .commit()

        syncPrefsToDeviceProtectedStorage(appCtx)

        val dev = getDeviceProtectedPreferences(appCtx)
        assertEquals("hello", dev.getString("synced_string", null))
        assertEquals(true, dev.getBoolean("synced_bool", false))
        assertEquals(42, dev.getInt("synced_int", 0))
    }

    @Test fun `sync is idempotent and updates stale device-protected values`() {
        shadowUser.setUserUnlocked(true)
        val defaultPrefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appCtx)

        defaultPrefs.edit().putString("k", "v1").commit()
        syncPrefsToDeviceProtectedStorage(appCtx)
        assertEquals("v1", getDeviceProtectedPreferences(appCtx).getString("k", null))

        defaultPrefs.edit().putString("k", "v2").commit()
        syncPrefsToDeviceProtectedStorage(appCtx)
        assertEquals("second sync should overwrite", "v2",
            getDeviceProtectedPreferences(appCtx).getString("k", null))
    }
}
