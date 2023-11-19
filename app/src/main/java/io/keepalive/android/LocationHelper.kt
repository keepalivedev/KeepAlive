package io.keepalive.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.google.android.gms.tasks.Task
import java.util.Locale


class LocationHelper(
    private val context: Context,
    private val myCallback: (Context, String) -> Unit,
) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var gpsEnabled = false
    private var networkEnabled = false

    // this would just let us cancel the request later if necessary...?
    private val cancellationToken = object : CancellationToken() {
        override fun onCanceledRequested(p0: OnTokenCanceledListener) =
            CancellationTokenSource().token

        override fun isCancellationRequested() = false
    }

    init {

        // check whether the GPS or network provider is enabled, though we currently aren't
        //  doing anything with that information...
        try {

            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        } catch (ex: Exception) {
            Log.e("LocationHelper", "Error checking GPS or network provider", ex)
        }

        Log.d(
            "LocationHelper",
            "GPS enabled: $gpsEnabled, network enabled: $networkEnabled"
        )
    }

    private fun processLocationResult(task: Task<Location>, locationSource: String) {

        // This block will be executed whether the task was successful or not
        if (task.isSuccessful && task.result != null) {
            val location = task.result

            // it doesn't matter where we get the location from, if we get one then we can
            //  geocode it and send it to the callback
            Log.d(
                "processLocationResult",
                "Location is ${location.latitude}, ${location.longitude} ${location.accuracy}"
            )

            // try to geocode the location and then execute the callback
            geocodeLocation(location)

        } else {

            Log.e(
                "processLocationResult",
                "Failed while trying to get the $locationSource location",
                task.exception
            )

            // if we just failed to get the current location then try to get the last location
            if (locationSource == "current") {
                getLastLocation()

                // otherwise if we fail to get the last location then just send the error message
            } else {
                Log.d("processLocationResult", "Unable to determine location, executing callback")
                myCallback(context, context.getString(R.string.location_invalid_message))
            }
        }
    }

    // depending on the current power state, try to get the current location or the last location
    //  and then geocode it and then pass it to the callback
    fun getLocationAndExecute() {


        Log.d("getLocationAndExecute", "Attempting to get location...")
        try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                Log.d(
                    "getLocationAndExecute",
                    "Power save mode is ${powerManager.isPowerSaveMode}. " +
                            "Is device idle? ${powerManager.isDeviceIdleMode}"
                )

                // if the device is in power save mode then we can't get the current location or it
                //  will just freeze and never return?
                if (powerManager.isDeviceIdleMode) {

                    Log.d(
                        "getLocationAndExecute",
                        "Device is in idle mode, not getting current location"
                    )
                    getLastLocation()

                } else {

                    // was originally done like this because we thought we needed to customize
                    //  the options but we really don't?
                    // https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder
                    val currentLocReq = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)

                        // this isn't respected in doze mode???
                        .setDurationMillis(30000)
                        //.setMaxUpdateAgeMillis(5000)
                        .build()

                    // this is more accurate and up to date than .lastLocation
                    fusedLocationClient.getCurrentLocation(currentLocReq, cancellationToken)
                        .addOnCompleteListener { task ->
                            processLocationResult(task, "current")
                        }
                }
            }
        } catch (e: Exception) {

            // if we for some reason fail while building the request then try
            //   to get the last location
            Log.e("getLocationAndExecute", "Failed getting current location?!", e)
            getLastLocation()
        }
    }

    // yes mom we checked if we have permissions...
    @SuppressLint("MissingPermission")
    fun getLastLocation() {

        Log.d("getLastLocation", "Attempting to get lastLocation...")

        try {

            fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                processLocationResult(task, "last")
            }

        } catch (e: Exception) {
            Log.e("getLastLocation", "Failed while getting last location:", e)

            // if we failed to get the last location then just send the error message
            myCallback(context, context.getString(R.string.location_invalid_message))
        }
    }

    // try to geocode the location and then execute the callback
    private fun geocodeLocation(loc: Location) {

        try {
            val geocoder = Geocoder(context, Locale.getDefault())

            // GeocodeListener is only available in API 33+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                val geocodeListener = Geocoder.GeocodeListener { addresses ->

                    Log.d("geocodeLocation", "listener done, geocode result: $addresses")
                    val addressString = processGeocodeResult(addresses)
                    val locationString = buildGeocodedLocationStr(addressString, loc)
                    myCallback(context, locationString)
                }

                geocoder.getFromLocation(loc.latitude, loc.longitude, 1, geocodeListener)
            } else {

                // the synchronous version is deprecated but nothing else available in <33
                val addresses: List<Address> =
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)!!

                Log.d("geocodeLocation", "geocode result: $addresses")
                val addressString = processGeocodeResult(addresses)
                val locationString = buildGeocodedLocationStr(addressString, loc)

                myCallback(context, locationString)
            }

        } catch (e: Exception) {
            Log.e("geocodeLocation", "Failed geocoding GPS coordinates?!", e)
        }
    }

    // take the list of possible addresses and build a string
    private fun processGeocodeResult(addresses: List<Address>): String {

        var addressString = ""

        if (addresses.isNotEmpty()) {
            Log.d("geocodeLocation", "Address has ${addresses[0].maxAddressLineIndex + 1} lines")

            // most addresses only have a single line?
            for (i in 0..addresses[0].maxAddressLineIndex) {

                val addressLine: String = addresses[0].getAddressLine(i)

                // include as many address lines as we can in the SMS
                // +2 because we are adding a period and a space
                if ((addressString.length + addressLine.length + 2) < AppController.SMS_MESSAGE_MAX_LENGTH) {
                    addressString += "$addressLine. "
                } else {
                    Log.d(
                        "geocodeLocation",
                        "Not adding address line, would exceed character limit: $addressLine"
                    )
                }
            }

        } else {
            Log.d("geocodeLocation", "No address results")
        }
        return addressString
    }

    // build the location string that will be sent to the callback
    private fun buildGeocodedLocationStr(addressStr: String, loc: Location): String {
        return if (addressStr == "") {
            String.format(
                context.getString(R.string.geocode_invalid_message),
                loc.latitude, loc.longitude, loc.accuracy
            )
        } else {
            String.format(
                context.getString(R.string.geocode_valid_message),
                loc.latitude, loc.longitude, loc.accuracy, addressStr
            )
        }
    }

}