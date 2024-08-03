package io.keepalive.android

import android.content.Context
import android.util.Log
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class WebhookSender(private val context: Context, private val config: WebhookConfig) {

    private val client: OkHttpClient = OkHttpClient.Builder().apply {

        // set the request timeouts from the config
        connectTimeout(config.timeout.toLong(), TimeUnit.SECONDS)
        readTimeout(config.timeout.toLong(), TimeUnit.SECONDS)
        writeTimeout(config.timeout.toLong(), TimeUnit.SECONDS)

        // Disable certificate verification if needed
        if (!config.verifyCertificate) {

            DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_disable_certificate_verification))

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            hostnameVerifier { _, _ -> true }
        }
    }.build()

    fun sendRequest(locationResult: LocationResult? = null, callback: WebhookCallback) {
        var retries = config.retries
        var responseCode = -1
        var lastException: Exception? = null

        var attemptNum = 1

        while (retries >= 0) {
            try {
                DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_webhook_attempt, attemptNum, config.retries + 1))
                val request = buildRequest(locationResult)

                // this should automatically close the response body
                client.newCall(request).execute().use { response ->
                    responseCode = response.code
                    when {

                        // if the response is considered successful then we can run the callback and return
                        response.isSuccessful -> {
                            DebugLogger.d("WebhookSender",context.getString(R.string.debug_log_request_successful, responseCode))

                            // if we are successful then we can exit the function
                            callback.onSuccess(responseCode)
                            return
                        }
                        // if the response code is in the 500s or 408/429 then we should retry
                        responseCode in 500..599 || response.code in listOf(408, 429) -> {
                            DebugLogger.d("WebhookSender",context.getString(R.string.debug_log_request_failed, responseCode))
                        }
                        // otherwise this should be considered a non-retryable error
                        else -> {
                            DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_request_failed_no_retry, responseCode))
                            callback.onError(responseCode.toString())
                            return
                        }
                    }
                }

                // this can occur if cert verification fails, host resolution fails, timeout reached, etc
            } catch (e: IOException) {

                DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_io_exception_sending_webhook, e.localizedMessage))
                lastException = e

                // not sure exactly what can cause this but if it happens we shouldn't retry?
                // maybe if there was an issue building the URL?
            } catch (e: Exception) {
                DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_unknown_exception_sending_webhook, e.localizedMessage))
                callback.onError(e.localizedMessage ?: "Unknown error")

                // don't retry if this is an unknown exception
                return
            }

            // decrement the retries
            retries--
            attemptNum++
        }

        Log.d("WebhookSender", "Failed to send webhook, final response code: $responseCode, last exception: $lastException")

        // if we get here with a responseCode it means that the request failed
        if (responseCode != -1) {
            callback.onFailure(responseCode)
        } else {
            // otherwise it means there was an exception
            callback.onError(lastException?.localizedMessage ?: "Unknown error")
        }

        DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_failed_max_retries))
        return
    }

    private fun buildRequest(locationResult: LocationResult? = null): Request {

        val urlBuilder = config.url.toHttpUrlOrNull()?.newBuilder() ?: throw IllegalArgumentException(context.getString(R.string.webhook_url_invalid_error_with_url, config.url))

        DebugLogger.d("WebhookSender", context.getString(R.string.debug_log_sending_request, urlBuilder.build(), locationResult))

        var requestBody: RequestBody? = null

        // if there is a location result then include it in the request based on the user config
        if (locationResult != null) {
            if (config.includeLocation == context.getString(R.string.webhook_location_query_parameters)) {

                // add the locationResult fields to the query parameters
                locationResult.let {
                    urlBuilder.apply {
                        addQueryParameter("lat", it.latitude.toString())
                        addQueryParameter("lon", it.longitude.toString())
                        addQueryParameter("acc", it.accuracy.toString())
                        addQueryParameter("address", it.geocodedAddress)
                        addQueryParameter("loc_message", it.formattedLocationString)
                    }
                }
            }

            // build the request body based on the config
            requestBody = when (config.includeLocation) {
                context.getString(R.string.webhook_location_body_json) -> {

                    // create a JSON object with the locationResult fields
                    val json = JSONObject().apply {
                        locationResult.let {
                            put("lat", it.latitude)
                            put("lon", it.longitude)
                            put("acc", it.accuracy)
                            put("address", it.geocodedAddress)
                            put("loc_message", it.formattedLocationString)
                        }
                    }
                    json.toString().toRequestBody("application/json".toMediaType())
                }

                context.getString(R.string.webhook_location_body_form) -> {

                    // create a form-encoded body with the locationResult fields
                    FormBody.Builder().apply {
                        locationResult.let {
                            add("lat", it.latitude.toString())
                            add("lon", it.longitude.toString())
                            add("acc", it.accuracy.toString())
                            add("address", it.geocodedAddress)
                            add("loc_message", it.formattedLocationString)
                        }
                    }.build()
                }

                // For GET requests or when data is in query params, we don't need a body
                else -> null
            }
        }

        val requestBuilder = Request.Builder().url(urlBuilder.build())

        // Set the appropriate HTTP method
        when (config.method) {
            context.getString(R.string.webhook_get) -> requestBuilder.get()
            context.getString(R.string.webhook_post) -> requestBuilder.post(requestBody ?: "".toRequestBody(null))

            // should only ever be GET or POST but just in case default to GET
            else -> requestBuilder.get()
        }

        // add the headers
        config.headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // add the user agent, if it doesn't already exist
        if (config.headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {

            // just include the app name and version and the okhttp version
            // don't include contact info because it is not technically us making the request...
            requestBuilder.addHeader("User-Agent", "KeepAlive/${BuildConfig.VERSION_NAME} okhttp/${OkHttp.VERSION}")
        }

        return requestBuilder.build()
    }
}