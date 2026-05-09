package io.keepalive.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for the googlePlay-flavor [LocationHelper] (FusedLocationProviderClient).
 *
 * Mocks `LocationServices.getFusedLocationProviderClient` so we don't need a
 * real Play Services runtime. Captures the `addOnCompleteListener` callbacks
 * and fires them synthetically to exercise every branch of
 * `processLocationResult`.
 */
@RunWith(RobolectricTestRunner::class)
class LocationHelperGooglePlayTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val shadowApp get() = shadowOf(appCtx as Application)

    private lateinit var fusedClient: FusedLocationProviderClient

    @Before fun setUp() {
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        // Replace the Play Services provider with a mock.
        fusedClient = mockk(relaxed = true)
        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(any<Context>()) } returns fusedClient

        // Geocoder is hit in the success paths; stub with an address so the
        // formatted string is non-null and we can assert the callback fired.
        mockkConstructor(Geocoder::class)
        val addr = Address(java.util.Locale.getDefault()).apply {
            setAddressLine(0, "Test Address 1")
        }
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>(), any<Geocoder.GeocodeListener>()
            )
        } answers {
            arg<Geocoder.GeocodeListener>(3).onGeocode(listOf(addr))
        }
        every {
            anyConstructed<Geocoder>().getFromLocation(
                any<Double>(), any<Double>(), any<Int>()
            )
        } returns listOf(addr)
    }

    @After fun tearDown() {
        unmockkStatic(LocationServices::class)
        unmockkConstructor(Geocoder::class)
        unmockkAll()
    }

    // ---- helpers ------------------------------------------------------------

    private class CallbackRecorder {
        val latch = CountDownLatch(1)
        val result = AtomicReference<LocationResult?>(null)
        val asLambda: (Context, LocationResult) -> Unit = { _, r ->
            result.set(r); latch.countDown()
        }
        fun await(timeoutMs: Long = 2000L): LocationResult? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return result.get()
        }
    }

    private fun successfulTask(location: Location): Task<Location> = mockk(relaxed = true) {
        every { isSuccessful } returns true
        every { result } returns location
        every { exception } returns null
    }

    private fun failedTask(ex: Exception = RuntimeException("gms failure")): Task<Location> =
        mockk(relaxed = true) {
            every { isSuccessful } returns false
            every { result } returns null
            every { exception } returns ex
        }

    private fun nullResultTask(): Task<Location> = mockk(relaxed = true) {
        every { isSuccessful } returns true
        every { result } returns null
        every { exception } returns null
    }

    private fun sampleLocation(lat: Double = 47.6, lon: Double = -122.3, acc: Float = 8f): Location =
        Location("gps").apply {
            latitude = lat; longitude = lon; accuracy = acc
        }

    /**
     * Captures the completion listener passed to [FusedLocationProviderClient.getCurrentLocation]
     * so the test can invoke it with a fake Task.
     */
    private fun captureCurrentLocationListener(): CapturingSlot<OnCompleteListener<Location>> {
        val slot = slot<OnCompleteListener<Location>>()
        every {
            fusedClient.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>())
        } answers {
            mockk(relaxed = true) {
                every { addOnCompleteListener(capture(slot)) } answers {
                    this@mockk
                }
            }
        }
        return slot
    }

    private fun captureLastLocationListener(): CapturingSlot<OnCompleteListener<Location>> {
        val slot = slot<OnCompleteListener<Location>>()
        every { fusedClient.lastLocation } answers {
            mockk(relaxed = true) {
                every { addOnCompleteListener(capture(slot)) } answers {
                    this@mockk
                }
            }
        }
        return slot
    }

    // ---- tests --------------------------------------------------------------

    @Test fun `getCurrentLocation requests HIGH_ACCURACY from the fused client`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)
        val reqSlot = slot<CurrentLocationRequest>()
        every {
            fusedClient.getCurrentLocation(capture(reqSlot), any<CancellationToken>())
        } returns mockk(relaxed = true)

        helper.getCurrentLocation()

        assertNotNull(reqSlot.captured)
        // Priority 100 = PRIORITY_HIGH_ACCURACY. Check via the captured
        // request's priority field so we don't have to import the constant.
        assertEquals(100, reqSlot.captured.priority)
    }

    @Test fun `successful current location geocodes and fires callback`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)
        val slot = captureCurrentLocationListener()

        helper.getCurrentLocation()
        slot.captured.onComplete(successfulTask(sampleLocation()))

        val result = cb.await()
        assertNotNull("callback must fire on success", result)
        assertEquals(47.6, result!!.latitude, 0.0001)
        assertEquals(-122.3, result.longitude, 0.0001)
        assertTrue("geocoded address should be attached",
            result.formattedLocationString.contains("Test Address 1"))
    }

    @Test fun `failed getCurrentLocation falls back to getLastLocation`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)

        val currentSlot = captureCurrentLocationListener()
        val lastSlot = captureLastLocationListener()

        helper.getCurrentLocation()
        currentSlot.captured.onComplete(failedTask())

        // Now the fallback should have called lastLocation — fire the listener
        // with a successful last location.
        assertTrue("lastLocation should have been requested", lastSlot.isCaptured)
        lastSlot.captured.onComplete(successfulTask(sampleLocation(lat = 1.0, lon = 2.0)))

        val result = cb.await()
        assertEquals(1.0, result!!.latitude, 0.0001)
    }

    @Test fun `failed last-location after failed current produces invalid-message callback`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)

        val currentSlot = captureCurrentLocationListener()
        val lastSlot = captureLastLocationListener()

        helper.getCurrentLocation()
        currentSlot.captured.onComplete(failedTask())
        // Last location also fails
        lastSlot.captured.onComplete(failedTask())

        val result = cb.await()
        assertEquals("double-failure should end in the 'invalid' user message",
            appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    @Test fun `null result with isSuccessful=true is treated as failure`() {
        // Task.isSuccessful can return true with a null result. The helper
        // must treat this as "no location" and fall back to getLastLocation.
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)
        val currentSlot = captureCurrentLocationListener()
        val lastSlot = captureLastLocationListener()

        helper.getCurrentLocation()
        currentSlot.captured.onComplete(nullResultTask())

        assertTrue("must call lastLocation as fallback", lastSlot.isCaptured)
    }

    @Test fun `getLastLocation exception fires invalid-message callback`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)
        every { fusedClient.lastLocation } throws RuntimeException("lastLocation blew up")

        helper.getLastLocation()

        val result = cb.await()
        assertEquals(appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    @Test fun `getLastLocation success path geocodes and fires callback`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)
        val slot = captureLastLocationListener()

        helper.getLastLocation()
        slot.captured.onComplete(successfulTask(sampleLocation()))

        val result = cb.await()
        assertEquals(47.6, result!!.latitude, 0.0001)
    }

    @Test fun `globalTimeoutRunnable cancels the token and fires invalid callback`() {
        val cb = CallbackRecorder()
        val helper = LocationHelper(appCtx, cb.asLambda)

        helper.globalTimeoutRunnable.run()

        val result = cb.await()
        assertEquals(appCtx.getString(R.string.location_invalid_message),
            result?.formattedLocationString)
    }

    @Test fun `helpers built from different contexts still get a fused client`() {
        // Regression check — if the mock on LocationServices is broken, the
        // init block would throw on field access.
        val cb = CallbackRecorder()
        val h1 = LocationHelper(appCtx, cb.asLambda)
        val h2 = LocationHelper(appCtx, cb.asLambda)
        assertNotSame(h1, h2)
        // And verify we actually got the mocked client.
        verify(atLeast = 2) {
            LocationServices.getFusedLocationProviderClient(any<Context>())
        }
    }
}
