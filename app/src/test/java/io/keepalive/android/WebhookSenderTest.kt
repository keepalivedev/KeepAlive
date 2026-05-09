package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests [WebhookSender] with a real in-process MockWebServer — no Android
 * services needed except resource lookups (Robolectric covers those).
 *
 * Covers:
 *  - Successful GET / POST requests
 *  - Retry on 5xx, 408, 429
 *  - No retry on 4xx (except 408/429)
 *  - Location payload formats (query, JSON body, form body)
 *  - Default User-Agent added when none in headers
 *  - Exception / failure / error callback paths
 */
@RunWith(RobolectricTestRunner::class)
class WebhookSenderTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun makeConfig(
        method: String = "GET",
        includeLocation: String = "",
        timeout: Int = 5,
        retries: Int = 0,
        headers: Map<String, String> = emptyMap()
    ) = WebhookConfig(
        url = server.url("/hook").toString(),
        method = method,
        includeLocation = includeLocation,
        timeout = timeout,
        retries = retries,
        verifyCertificate = true,
        headers = headers
    )

    private fun sampleLocation() = LocationResult(
        latitude = 40.5,
        longitude = -70.25,
        accuracy = 12.0f,
        geocodedAddress = "Somewhere",
        formattedLocationString = "You are at 40.5,-70.25"
    )

    private class RecordingCallback : WebhookCallback {
        val successCodes = mutableListOf<Int>()
        val failureCodes = mutableListOf<Int>()
        val errors = mutableListOf<String>()

        override fun onSuccess(responseCode: Int) { successCodes += responseCode }
        override fun onFailure(responseCode: Int) { failureCodes += responseCode }
        override fun onError(errorMessage: String) { errors += errorMessage }
    }

    // ---- success paths ------------------------------------------------------

    @Test fun `successful GET invokes onSuccess with response code`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(method = "GET")).sendRequest(callback = cb)

        assertEquals(listOf(200), cb.successCodes)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun `successful POST sends empty body when no location config`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(method = "POST")).sendRequest(callback = cb)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(listOf(200), cb.successCodes)
    }

    // ---- retry paths --------------------------------------------------------

    @Test fun `5xx response triggers retries up to the configured max`() {
        repeat(3) { server.enqueue(MockResponse(code = 500)) }
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(retries = 2)).sendRequest(callback = cb)

        assertEquals("retries=2 means 3 attempts total", 3, server.requestCount)
        assertEquals(listOf(500), cb.failureCodes)
    }

    @Test fun `retry eventually succeeds after a 500`() {
        server.enqueue(MockResponse(code = 500))
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(retries = 1)).sendRequest(callback = cb)

        assertEquals(2, server.requestCount)
        assertEquals(listOf(200), cb.successCodes)
    }

    @Test fun `429 is retried`() {
        server.enqueue(MockResponse(code = 429))
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(retries = 1)).sendRequest(callback = cb)

        assertEquals(listOf(200), cb.successCodes)
    }

    @Test fun `4xx that is not 408 or 429 is NOT retried, calls onError`() {
        server.enqueue(MockResponse(code = 404))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(retries = 3)).sendRequest(callback = cb)

        assertEquals("404 is terminal, no retry", 1, server.requestCount)
        assertTrue(cb.failureCodes.isEmpty())
        assertEquals(listOf("404"), cb.errors)
    }

    // ---- exception path -----------------------------------------------------

    @Test fun `connection failure after exhausting retries triggers onError`() {
        server.close()  // close before any request goes out
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig(timeout = 1, retries = 0)).sendRequest(callback = cb)

        assertTrue("connection failure triggers onError, not onFailure",
            cb.errors.isNotEmpty())
        assertTrue(cb.successCodes.isEmpty())
    }

    // ---- location payload formats ------------------------------------------

    @Test fun `query-param location mode appends lat, lon, acc to the URL`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(
            appCtx,
            makeConfig(
                method = "GET",
                includeLocation = appCtx.getString(R.string.webhook_location_query_parameters)
            )
        ).sendRequest(locationResult = sampleLocation(), callback = cb)

        val recorded = server.takeRequest()
        val path = recorded.url.toString()
        assertTrue("lat in query: $path", path.contains("lat=40.5"))
        assertTrue("lon in query: $path", path.contains("lon=-70.25"))
        assertTrue("acc in query: $path", path.contains("acc=12"))
    }

    @Test fun `JSON body location mode posts application-json`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(
            appCtx,
            makeConfig(
                method = "POST",
                includeLocation = appCtx.getString(R.string.webhook_location_body_json)
            )
        ).sendRequest(locationResult = sampleLocation(), callback = cb)

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8() ?: ""
        assertEquals("application/json; charset=utf-8",
            recorded.headers["Content-Type"])
        assertTrue("JSON body has lat: $body", body.contains("\"lat\""))
        assertTrue("JSON body has formatted location: $body",
            body.contains("You are at 40.5,-70.25"))
    }

    @Test fun `form body location mode posts x-www-form-urlencoded`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(
            appCtx,
            makeConfig(
                method = "POST",
                includeLocation = appCtx.getString(R.string.webhook_location_body_form)
            )
        ).sendRequest(locationResult = sampleLocation(), callback = cb)

        val recorded = server.takeRequest()
        val body = recorded.body?.utf8() ?: ""
        assertEquals("application/x-www-form-urlencoded",
            recorded.headers["Content-Type"])
        assertTrue("form body has lat=40.5: $body", body.contains("lat=40.5"))
    }

    // ---- headers ------------------------------------------------------------

    @Test fun `custom headers are included on the request`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(
            appCtx,
            makeConfig(headers = mapOf("X-Custom" to "hello", "Authorization" to "Bearer x"))
        ).sendRequest(callback = cb)

        val recorded = server.takeRequest()
        assertEquals("hello", recorded.headers["X-Custom"])
        assertEquals("Bearer x", recorded.headers["Authorization"])
    }

    @Test fun `default User-Agent is added when not already present`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(appCtx, makeConfig()).sendRequest(callback = cb)

        val ua = server.takeRequest().headers["User-Agent"]
        assertNotNull(ua)
        assertTrue("default UA should identify KeepAlive: $ua", ua!!.startsWith("KeepAlive/"))
    }

    @Test fun `custom User-Agent overrides the default`() {
        server.enqueue(MockResponse(code = 200))
        val cb = RecordingCallback()

        WebhookSender(
            appCtx,
            makeConfig(headers = mapOf("User-Agent" to "CustomAgent/1"))
        ).sendRequest(callback = cb)

        assertEquals("CustomAgent/1", server.takeRequest().headers["User-Agent"])
    }
}
