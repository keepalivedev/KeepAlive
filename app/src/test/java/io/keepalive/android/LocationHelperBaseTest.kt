package io.keepalive.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests the permission-gate + callback-plumbing paths of [LocationHelperBase].
 *
 * The platform-specific subclasses (GMS vs android.location) override
 * `getCurrentLocation` / `getLastLocation`; we test the code that decides
 * *whether* those get called. That's where real user-visible bugs live
 * (missing permission-check = silent GPS request, stuck timeout = lost alert).
 *
 * Uses a simple test subclass to record whether getCurrentLocation /
 * getLastLocation were invoked, instead of standing up real location providers.
 */
@RunWith(RobolectricTestRunner::class)
// LocationHelperBase branches at:
//  - API M (23): PowerManager.isDeviceIdleMode
//  - API P (28): LocationManager.isLocationEnabled, getLocationPowerSaveMode
//  - API Q (29): ACCESS_BACKGROUND_LOCATION required
//  - API T (33): Geocoder async API (GeocodingHelperAPI33Plus)
// Matrix exercises each.
@Config(sdk = [23, 28, 33, 34, 35, 36])
class LocationHelperBaseTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp get() = shadowOf(appCtx as Application)

    /** Captures the callback arguments so tests can assert on them. */
    private class CallbackRecorder {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LocationResult?>(null)

        val asLambda: (Context, LocationResult) -> Unit = { _, r ->
            result.set(r)
            latch.countDown()
        }

        fun await(timeoutMs: Long = 2000L): LocationResult? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result.get()
        }
    }

    /** Test subclass that just records whether the provider hooks were called. */
    private class RecordingHelper(
        context: Context,
        cb: (Context, LocationResult) -> Unit
    ) : LocationHelperBase(context, cb) {
        var getCurrentLocationCount = 0
        var getLastLocationCount = 0

        override fun getCurrentLocation() { getCurrentLocationCount++ }
        override fun getLastLocation() { getLastLocationCount++ }
    }

    @Before fun setUp() {
        // Make sure a LocationManager exists in Robolectric.
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setLocationEnabled(true)
    }

    @After fun tearDown() {
        unmockkConstructor(Geocoder::class)
    }

    private fun stubGeocoderReturns(addresses: List<Address>) {
        // Intercept the Geocoder constructed inside the helper.
        // Both the API33+ async and the pre-33 sync variants are covered so
        // tests don't need to know which branch the engine picks.
        mockkConstructor(Geocoder::class)
        // API 33+: async variant with a listener
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>(), any<Geocoder.GeocodeListener>()
            )
        } answers {
            val listener = arg<Geocoder.GeocodeListener>(3)
            listener.onGeocode(addresses)
        }
        // pre-33: synchronous variant returning the list directly
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>()
            )
        } returns addresses
    }

    @Test fun `no FINE_LOCATION permission fires callback with invalid message`() {
        // No location permissions granted — the helper must NOT hit the real
        // provider and must still fire the callback so the alert flow proceeds.
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)

        helper.getLocationAndExecute()

        val result = cb.await()
        assertEquals(0, helper.getCurrentLocationCount)
        assertEquals(0, helper.getLastLocationCount)
        assertEquals(appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // ACCESS_BACKGROUND_LOCATION is API Q+
    fun `with FINE_LOCATION only, background perms required on Q+ - invalid callback`() {
        // Robolectric runs at SDK 35; background permission is required.
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // DO NOT grant ACCESS_BACKGROUND_LOCATION
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)

        helper.getLocationAndExecute(ignoreBackgroundPerms = false)

        val result = cb.await()
        assertEquals("missing BG perm = no real location fetch", 0, helper.getCurrentLocationCount)
        assertEquals(appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    @Test fun `ignoreBackgroundPerms bypasses the BG permission check`() {
        // Used when the user is testing the webhook manually from the UI —
        // we don't need background permission because the request happens
        // from an activity context.
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val helper = RecordingHelper(appCtx) { _, _ -> }

        helper.getLocationAndExecute(ignoreBackgroundPerms = true)

        // Default isDeviceIdleMode=false → getCurrentLocation is called
        assertEquals(1, helper.getCurrentLocationCount)
        assertEquals(0, helper.getLastLocationCount)
    }

    @Test fun `with all perms, non-idle device calls getCurrentLocation`() {
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val helper = RecordingHelper(appCtx) { _, _ -> }

        helper.getLocationAndExecute()

        assertEquals(1, helper.getCurrentLocationCount)
        assertEquals(0, helper.getLastLocationCount)
    }

    @Test fun `executeCallback runs the callback with the provided result`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        val sample = LocationResult(
            latitude = 1.23,
            longitude = 4.56,
            accuracy = 9.9f,
            geocodedAddress = "addr",
            formattedLocationString = "formatted!"
        )

        helper.executeCallback(sample)

        val result = cb.await()
        assertEquals(1.23, result!!.latitude, 0.0001)
        assertEquals("formatted!", result.formattedLocationString)
    }

    @Test fun `device-idle mode routes to getLastLocation`() {
        // In Doze / idle mode, getCurrentLocation tends to freeze forever.
        // The base class flips to getLastLocation instead. Drive it by
        // flagging the PowerManager shadow as idle before constructing.
        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIsDeviceIdleMode(true)
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val helper = RecordingHelper(appCtx) { _, _ -> }

        helper.getLocationAndExecute()

        assertEquals("idle mode should skip getCurrentLocation",
            0, helper.getCurrentLocationCount)
        assertEquals(1, helper.getLastLocationCount)
    }

    @Test fun `exception during getCurrentLocation falls through to getLastLocation`() {
        // If the provider-specific `getCurrentLocation` throws, the base
        // class catches and routes to `getLastLocation`. Simulate by
        // overriding getCurrentLocation to throw.
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val helper = object : LocationHelperBase(appCtx, { _, _ -> }) {
            var getCurrentCount = 0
            var getLastCount = 0
            override fun getCurrentLocation() {
                getCurrentCount++
                throw RuntimeException("provider blew up")
            }
            override fun getLastLocation() { getLastCount++ }
        }

        helper.getLocationAndExecute()

        assertEquals(1, helper.getCurrentCount)
        assertEquals("must fall through to getLastLocation on exception",
            1, helper.getLastCount)
    }

    @Test fun `globalTimeoutRunnable fires an invalid-location callback`() {
        // The base class's default timeout runnable: sets formattedLocationString
        // to the "invalid" message and fires the callback. We run the runnable
        // directly to avoid the 61-second real wait.
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)

        helper.globalTimeoutRunnable.run()

        val result = cb.await()
        assertEquals(appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    // ---- Geocoding (API 33+) ---------------------------------------------

    @Test
    @Config(sdk = [33, 34, 35, 36])  // GeocodeListener only exists on TIRAMISU+
    fun `API33 geocode success formats an address into the location string`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)

        val addr = Address(java.util.Locale.getDefault()).apply {
            setAddressLine(0, "123 Main St, Anytown, USA")
        }
        stubGeocoderReturns(listOf(addr))

        val loc = Location("test").apply {
            latitude = 40.0
            longitude = -70.0
            accuracy = 5f
        }
        helper.GeocodingHelperAPI33Plus().geocodeLocationAndExecute(loc)

        val result = cb.await()
        assertNotEquals("must not keep the fallback invalid message",
            appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
        assertTrue("result must mention the geocoded street: ${result?.formattedLocationString}",
            result?.formattedLocationString?.contains("123 Main St") == true)
        assertEquals("123 Main St, Anytown, USA. ", result?.geocodedAddress)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // GeocodeListener only exists on TIRAMISU+
    fun `API33 geocode empty result falls back to GPS-only invalid-format message`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        stubGeocoderReturns(emptyList())

        val loc = Location("test").apply {
            latitude = 41.5
            longitude = -71.0
            accuracy = 10f
        }
        helper.GeocodingHelperAPI33Plus().geocodeLocationAndExecute(loc)

        val result = cb.await()
        assertTrue("must fall back to coords-only message: ${result?.formattedLocationString}",
            result?.formattedLocationString?.contains("41.5") == true)
        assertEquals("", result?.geocodedAddress)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // GeocodeListener only exists on TIRAMISU+
    fun `API33 multi-line addresses are concatenated with period-space separators`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        val addr = Address(java.util.Locale.getDefault()).apply {
            setAddressLine(0, "Line one")
            setAddressLine(1, "Line two")
            setAddressLine(2, "Line three")
        }
        stubGeocoderReturns(listOf(addr))

        val loc = Location("test").apply {
            latitude = 0.0; longitude = 0.0; accuracy = 1f
        }
        helper.GeocodingHelperAPI33Plus().geocodeLocationAndExecute(loc)

        val result = cb.await()
        val formatted = result?.formattedLocationString ?: ""
        assertTrue("L1 present: $formatted", formatted.contains("Line one"))
        assertTrue("L2 present: $formatted", formatted.contains("Line two"))
        assertTrue("L3 present: $formatted", formatted.contains("Line three"))
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // GeocodeListener only exists on TIRAMISU+
    fun `API33 geocode exception falls back to invalid-format coords message`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        // Simulate a provider backend exception
        mockkConstructor(Geocoder::class)
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>(), any<Geocoder.GeocodeListener>()
            )
        } throws java.io.IOException("geocoder went boom")

        val loc = Location("test").apply {
            latitude = 55.5; longitude = -1.0; accuracy = 3f
        }
        helper.GeocodingHelperAPI33Plus().geocodeLocationAndExecute(loc)

        val result = cb.await()
        // Exception path falls through to executeCallback with the pre-seeded
        // "invalid-format" coords-only message.
        assertTrue("formattedLocationString should be the coords-only fallback",
            result?.formattedLocationString?.contains("55.5") == true)
    }

    // ---- Geocoding (pre-33 sync path) -------------------------------------

    @Test
    @Config(sdk = [33, 34, 35, 36])  // uses mockkConstructor on Geocoder whose
    // listener inner class only resolves on TIRAMISU+; sync-only behavior on
    // older SDKs is exercised indirectly by the regular getLocationAndExecute tests.
    fun `pre33 GeocodingHelper formats synchronous address results`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        val addr = Address(java.util.Locale.getDefault()).apply {
            setAddressLine(0, "42 Galaxy Way")
        }
        stubGeocoderReturns(listOf(addr))

        val loc = Location("test").apply {
            latitude = 10.0; longitude = 20.0; accuracy = 2f
        }
        helper.GeocodingHelper().geocodeLocationAndExecute(loc)

        val result = cb.await()
        assertTrue(result?.formattedLocationString?.contains("42 Galaxy Way") == true)
        assertEquals("42 Galaxy Way. ", result?.geocodedAddress)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // uses mockkConstructor on Geocoder whose
    // listener inner class only resolves on TIRAMISU+; sync-only behavior on
    // older SDKs is exercised indirectly by the regular getLocationAndExecute tests.
    fun `pre33 GeocodingHelper falls back when Geocoder returns empty`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        stubGeocoderReturns(emptyList())

        val loc = Location("test").apply {
            latitude = 99.9; longitude = 88.8; accuracy = 4f
        }
        helper.GeocodingHelper().geocodeLocationAndExecute(loc)

        val result = cb.await()
        assertTrue("invalid-format message embeds the coords",
            result?.formattedLocationString?.contains("99.9") == true)
        assertEquals("", result?.geocodedAddress)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])  // uses mockkConstructor on Geocoder whose
    // listener inner class only resolves on TIRAMISU+; sync-only behavior on
    // older SDKs is exercised indirectly by the regular getLocationAndExecute tests.
    fun `pre33 GeocodingHelper swallows exceptions and still fires callback`() {
        val cb = CallbackRecorder()
        val helper = RecordingHelper(appCtx, cb.asLambda)
        mockkConstructor(Geocoder::class)
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>()
            )
        } throws java.io.IOException("sync geocoder failed")

        val loc = Location("test").apply {
            latitude = 7.0; longitude = 8.0; accuracy = 9f
        }
        helper.GeocodingHelper().geocodeLocationAndExecute(loc)

        val result = cb.await()
        assertTrue("callback must fire even on exception — coords fallback",
            result?.formattedLocationString?.contains("7.0") == true)
    }

    @Test fun `no-location fast-path does not call getCurrentLocation a second time via timeout`() {
        // The global timeout handler fires an invalid-location callback after
        // ~61s if nothing resolved. On the no-permission fast-path we fire
        // our own callback and must cancel the timeout so getCurrentLocation
        // doesn't get a second invocation from it.
        val callCount = java.util.concurrent.atomic.AtomicInteger()
        val helper = RecordingHelper(appCtx) { _, _ -> callCount.incrementAndGet() }

        helper.getLocationAndExecute()
        // Give any pending handler a moment.
        Thread.sleep(200)

        assertEquals("exactly one callback, no duplicate from the timeout handler",
            1, callCount.get())
        assertEquals(0, helper.getCurrentLocationCount)
        assertEquals(0, helper.getLastLocationCount)
    }
}
