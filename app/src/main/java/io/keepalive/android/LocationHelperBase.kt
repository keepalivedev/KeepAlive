package io.keepalive.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors


// base class for the location helper, this will be extended based on whether
//  we are using android.location (for f-droid) or com.google.android.gms.location (for google play)
open class LocationHelperBase(
    val context: Context,
    val myCallback: (Context, LocationResult) -> Unit,
) {

    // todo should we be using LocationManagerCompat instead?
    lateinit var locationManager: LocationManager
    private var locationEnabled = false
    private var isDeviceIdleMode = false
    private var isPowerSaveMode = false
    private var locationPowerSaveMode = 0
    var availableProviders: MutableList<String> = arrayListOf()

    // how long to wait for location requests to complete before timing out
    val locationRequestTimeoutLength = 30000L

    // how long to wait for geocoding requests to complete before timing out
    val geocodingRequestTimeoutLength = 30000L

    // how long to wait for everything to complete before timing out
    private val globalTimeoutLength = 61000L

    // background executor to be used with fDroid location requests and the callback
    val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    // background handler to be used with timeout handlers here and in fDroid version
    val backgroundHandler = Handler(HandlerThread("LocationBackgroundThread").apply { start() }.looper)

    // timeout handler to make sure the entire location process doesn't hang
    private val globalTimeoutHandler = Handler(backgroundHandler.looper)

    //private var locationString = ""
    private val geocodingTimeoutHandler = Handler(backgroundHandler.looper)
    var locationResult = LocationResult(0.0, 0.0, 0.0f, "", "")

    init {

        // I am worried that some of this may throw an exception in certain edge cases so wrap
        //  everything in a try/catch and use separate vars
        try {
            // check whether the GPS, network and location services are enabled
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationEnabled = locationManager.isLocationEnabled
            }

            availableProviders = locationManager.getProviders(true)

            // remove the passive provider because it relies on another app requesting location
            //  as a way to save battery. this causes it to time out most of the time
            // note that this is only relevant when using android.location
            if (LocationManager.PASSIVE_PROVIDER in availableProviders) {
                availableProviders.remove(LocationManager.PASSIVE_PROVIDER)
            }

            // check whether the device is in idle or power save mode
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            isPowerSaveMode = powerManager.isPowerSaveMode

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isDeviceIdleMode = powerManager.isDeviceIdleMode
            }

            // also check the location power save mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                locationPowerSaveMode = powerManager.getLocationPowerSaveMode()

                val modeString = when (locationPowerSaveMode) {
                    PowerManager.LOCATION_MODE_NO_CHANGE -> "LOCATION_MODE_NO_CHANGE"
                    PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF -> "LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF"
                    PowerManager.LOCATION_MODE_FOREGROUND_ONLY -> "LOCATION_MODE_FOREGROUND_ONLY"
                    PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF -> "LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF"
                    PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF -> "LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF"
                    else -> "unknown"
                }

                Log.d("LocationHelperBase", "Location power save mode: $modeString")

                if (locationPowerSaveMode != PowerManager.LOCATION_MODE_NO_CHANGE) {

                    DebugLogger.d(
                        "LocationHelperBase",
                        context.getString(R.string.debug_log_location_power_mode_warning, modeString)
                    )
                }
            }

        } catch (e: Exception) {
            DebugLogger.d("LocationHelperBase", context.getString(R.string.debug_log_location_helper_init_error), e)
        }

        Log.d(
            "LocationHelperBase",
            "Location enabled: $locationEnabled. Available providers: $availableProviders"
        )
    }

    // these will get overridden with platform specific implementations based on whether
    //  we are using android.location or com.google.android.gms.location
    open fun getLastLocation() {}
    open fun getCurrentLocation() {}

    open val globalTimeoutRunnable = Runnable {
        DebugLogger.d("globalTimeoutRunnable", context.getString(R.string.debug_log_timeout_reached_getting_location))

        locationResult.formattedLocationString = context.getString(R.string.location_invalid_message)
        myCallback(context, locationResult)
    }

    private fun startGlobalTimeoutHandler() {
        globalTimeoutHandler.postDelayed(globalTimeoutRunnable, globalTimeoutLength)
    }

    private fun stopGlobalTimeoutHandler() {
        globalTimeoutHandler.removeCallbacks(globalTimeoutRunnable)
    }

    // timeout handler for the geocoding process, really only necessary in API 33+
    //  because the old geocoding method is synchronous
    private val geocodingTimeoutRunnable = Runnable {
        DebugLogger.d("geocodingTimeoutRunnable", context.getString(R.string.debug_log_geocoding_timeout_reached, locationResult.formattedLocationString))

        // the global timeout handler should still be running so need to stop it
        stopGlobalTimeoutHandler()

        myCallback(context, locationResult)
    }

    private fun startGeocodingTimeoutHandler() {
        geocodingTimeoutHandler.postDelayed(
            geocodingTimeoutRunnable,
            geocodingRequestTimeoutLength
        )
    }

    private fun stopGeocodingTimeoutHandler() {
        geocodingTimeoutHandler.removeCallbacks(geocodingTimeoutRunnable)
    }

    fun executeCallback(locationResult: LocationResult) {

        // make sure the callback is executed on a background thread
        backgroundExecutor.execute {

            // stop the global timeout handler and execute the callback
            stopGlobalTimeoutHandler()
            myCallback(context, locationResult)
        }
    }

    // depending on the current power state, try to get the current location or the last location
    //  and then geocode it and then pass it to the callback
    // all paths should result in the callback being executed or we may fail to send an alert!
    fun getLocationAndExecute(ignoreBackgroundPerms: Boolean = false) {

        startGlobalTimeoutHandler()

        DebugLogger.d("getLocationAndExecute", context.getString(R.string.debug_log_attempting_to_get_location))
        try {
            // background location permissions are only needed for API 29+
            val haveBackgroundLocPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED &&

                // allow for the background permissions to be ignored because they aren't needed
                //  when testing the webhook
                (haveBackgroundLocPerms || ignoreBackgroundPerms)
            ) {

                DebugLogger.d(
                    "getLocationAndExecute",
                    context.getString(R.string.debug_log_power_and_idle_status, isPowerSaveMode, isDeviceIdleMode)
                )

                // todo this might not be the case since the switch to AlertService...
                // if the device is in power save mode then we can't get the current location or it
                //  will just freeze and never return?
                if (!isDeviceIdleMode) {

                    // do a bunch of stuff to try to get the current location and then
                    //  execute the callback with whatever the results are
                    getCurrentLocation()

                } else {

                    DebugLogger.d(
                        "getLocationAndExecute",
                        context.getString(R.string.debug_log_device_idle_not_getting_current_location)
                    )

                    // if the device is in idle mode then try to get the last location
                    getLastLocation()
                }
            } else {

                // if we don't have location permissions then just execute the callback
                DebugLogger.d("getLocationAndExecute", context.getString(R.string.debug_log_no_location_permission_executing_callback))

                locationResult.formattedLocationString = context.getString(R.string.location_invalid_message)
                stopGlobalTimeoutHandler()
                myCallback(context, locationResult)
            }
        } catch (e: Exception) {

            // if we for some reason fail while building the request then try
            //   to get the last location
            DebugLogger.d("getLocationAndExecute", context.getString(R.string.debug_log_failed_getting_current_location), e)
            getLastLocation()
        }
    }

    // had to create a separate class to avoid a ClassNotFoundException on API <33
    //  because the GeocodeListener is not available but it compiles it anyway
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    inner class GeocodingHelperAPI33Plus {

        // try to geocode the location and then execute the callback
        // all paths need to lead to the callback being executed or we may fail to send an alert!!
        fun geocodeLocationAndExecute(loc: Location) {

            try {
                Log.d(
                    "geocodeLocAndExecute",
                    "Geocoding location: ${loc.latitude}, ${loc.longitude}, ${loc.accuracy}"
                )

                // default to a message indicating we couldn't geocode the location and just include
                //  the raw GPS coordinates
                locationResult.formattedLocationString = String.format(
                    context.getString(R.string.geocode_invalid_message),
                    loc.latitude.toString(), loc.longitude.toString(), loc.accuracy.toString()
                )

                // start a timeout handler in case the geocoder hangs
                startGeocodingTimeoutHandler()

                val geocoder = Geocoder(context, Locale.getDefault())

                val geocodeListener = Geocoder.GeocodeListener { addresses ->

                    Log.d(
                        "geocodeLocAndExecute",
                        "listener done, geocode result: $addresses"
                    )
                    val addressString = processGeocodeResult(addresses)

                    locationResult.geocodedAddress = addressString
                    locationResult.formattedLocationString = buildGeocodedLocationStr(addressString, loc)

                    // execute the callback with the new location string
                    stopGeocodingTimeoutHandler()
                    executeCallback(locationResult)
                }

                geocoder.getFromLocation(loc.latitude, loc.longitude, 1, geocodeListener)
                return

            } catch (e: Exception) {
                DebugLogger.d("geocodeLocationAndExecute", context.getString(R.string.debug_log_failed_geocoding_gps_coordinates), e)
            }

            // if we aren't using geocode listener or if there was an error
            stopGeocodingTimeoutHandler()
            executeCallback(locationResult)
        }
    }

    inner class GeocodingHelper {

        // try to geocode the location and then execute the callback
        // all paths need to lead to the callback being executed or we may fail to send an alert!!
        fun geocodeLocationAndExecute(loc: Location) {

            try {
                Log.d(
                    "geocodeLocAndExecute",
                    "Geocoding location: ${loc.latitude}, ${loc.longitude}, ${loc.accuracy}"
                )

                // default to a message indicating we couldn't geocode the location and just include
                //  the raw GPS coordinates
                locationResult.formattedLocationString = String.format(
                    context.getString(R.string.geocode_invalid_message),
                    loc.latitude.toString(), loc.longitude.toString(), loc.accuracy.toString()
                )

                // start a timeout handler in case the geocoder hangs
                startGeocodingTimeoutHandler()

                val geocoder = Geocoder(context, Locale.getDefault())

                // the synchronous version is deprecated but nothing else available in <33
                val addresses: List<Address> =
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1) ?: emptyList()

                Log.d("geocodeLocAndExecute", "geocode result: $addresses")
                val addressString = processGeocodeResult(addresses)

                locationResult.geocodedAddress = addressString
                locationResult.formattedLocationString = buildGeocodedLocationStr(addressString, loc)

            } catch (e: Exception) {
                DebugLogger.d("geocodeLocationAndExecute", context.getString(R.string.debug_log_failed_geocoding_gps_coordinates), e)
            }

            // if we aren't using geocode listener or if there was an error
            stopGeocodingTimeoutHandler()

            executeCallback(locationResult)
        }
    }

    // take the list of possible addresses and build a string
    private fun processGeocodeResult(addresses: List<Address>): String {

        var addressString = ""

        if (addresses.isNotEmpty()) {
            Log.d(
                "processGeocodeResult",
                "Address has ${addresses[0].maxAddressLineIndex + 1} lines"
            )

            // we should have only requested a single address so just check the first in the list
            // most addresses only have a single line?
            for (i in 0..addresses[0].maxAddressLineIndex) {

                val addressLine: String = addresses[0].getAddressLine(i)

                // include as many address lines as we can in the SMS
                // +2 because we are adding a period and a space
                if ((addressString.length + addressLine.length + 2) < AppController.SMS_MESSAGE_MAX_LENGTH) {
                    addressString += "$addressLine. "
                } else {
                    Log.d(
                        "processGeocodeResult",
                        "Not adding address line, would exceed character limit: $addressLine"
                    )
                }
            }

        } else {
            DebugLogger.d("processGeocodeResult", context.getString(R.string.debug_log_no_address_results))
        }
        return addressString
    }

    // build the location string that will be sent to the callback
    private fun buildGeocodedLocationStr(addressStr: String, loc: Location): String {
        return if (addressStr == "") {
            String.format(
                context.getString(R.string.geocode_invalid_message),
                loc.latitude.toString(), loc.longitude.toString(), loc.accuracy.toString()
            )
        } else {
            String.format(
                context.getString(R.string.geocode_valid_message),
                loc.latitude.toString(), loc.longitude.toString(), loc.accuracy.toString(), addressStr
            )
        }
    }
}

data class LocationResult(
    var latitude: Double,
    var longitude: Double,
    var accuracy: Float,
    var geocodedAddress: String,
    var formattedLocationString: String
)