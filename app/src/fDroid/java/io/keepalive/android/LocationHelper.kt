package io.keepalive.android

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet


// implementation for use with android.location APIs so that its compatible with F-Droid
class LocationHelper(
    context: Context,
    myCallback: (Context, String) -> Unit
) : LocationHelperBase(context, myCallback) {


    // override the function to get the current location
    override fun getCurrentLocation() {
        CurrentLocationProcessor().getCurrentLocationAndExecute()
    }

    // override the function to get the last location
    override fun getLastLocation() {

        DebugLogger.d("getLastLocation", "Attempting to get lastLocation...")

        try {

            // attempt to get the most accurate last known location from the available providers
            val lastLocation: Location? = getBestLastKnownLocation()

            if (lastLocation != null) {
                DebugLogger.d(
                    "getLastLocation", "best lastKnownLocation is from provider " +
                            "${lastLocation.provider} with accuracy ${lastLocation.accuracy}"
                )

                // attempt to geocode the location and then execute the callback
                GeocodingHelper().geocodeLocationAndExecute(lastLocation)
                return

            } else {
                DebugLogger.d("getLastLocation", "Unable to determine location")
            }

        } catch (e: Exception) {
            DebugLogger.d("getLastLocation", "Failed while getting last location:", e)
        }

        // if the location was null or there was an error then execute the callback
        //  with an error message
        executeCallback(context.getString(R.string.location_invalid_message))
    }

    // check all available providers to try to get the best last known location
    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {

        var bestLocation: Location? = null

        // iterate through the enabled providers and try to get the last
        //  known location from each
        for (provider in availableProviders) {

            try {
                val providerLocation = locationManager.getLastKnownLocation(provider)

                Log.d(
                    "getBestLastKnownLoc",
                    "lastKnownLocation with provider $provider is $providerLocation"
                )

                // if the location isn't null and is better than the current one
                //  (smaller accuracy values are better)
                if (providerLocation != null &&
                    (bestLocation == null || providerLocation.accuracy < bestLocation.accuracy)
                ) {
                    bestLocation = providerLocation
                }

            } catch (e: Exception) {
                Log.e(
                    "getBestLastKnownLoc",
                    "Failed getting lastKnownLocation with provider $provider", e
                )
            }
        }
        return bestLocation
    }

    // class to handle everything related to getting the current location
    inner class CurrentLocationProcessor {
        private val locations = ConcurrentHashMap<String, Location>()
        private val timeoutHandler = Handler(context.mainLooper)
        private val cancellationSignal = CancellationSignal()
        private val receivedProviders = CopyOnWriteArraySet<String>()

        @SuppressLint("MissingPermission")
        fun getCurrentLocationAndExecute() {

            Log.d("LocationComparator", "Available providers: $availableProviders")

            // start a timeout handler to ensure we don't hang while getting locations
            startTimeoutHandler()

            for (provider in availableProviders) {

                // if this is API 30+ then we can use the new getCurrentLocation method
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                    locationManager.getCurrentLocation(
                        provider, cancellationSignal,
                        context.mainExecutor, ::processProviderLocationResult
                    )
                } else {

                    // if this is API 29 or lower then we need to use the deprecated
                    //  requestSingleUpdate method
                    locationManager.requestSingleUpdate(
                        provider,
                        locationListener,
                        context.mainLooper
                    )
                }
            }
        }

        // listener to be used with the deprecated locationManager.requestSingleUpdate
        private val locationListener = LocationListener { location ->

            Log.d("locationListener", "Received location: $location")
            processProviderLocationResult(location)
        }

        // both the deprecated and new methods will pass their results to this function
        // once a result from each provider has been received it will process the locations
        //  to find the 'best' one
        private fun processProviderLocationResult(loc: Location?) {

            if (loc != null) {

                // Use provider as key if not null, else return
                val providerKey = loc.provider ?: return

                Log.d(
                    "processProviderLocRes", "Location from provider $providerKey at " +
                            "${getDateTimeStrFromTimestamp(loc.time)} with acc ${loc.accuracy}: $loc"
                )

                // if this is a new location that we don't already have
                if (receivedProviders.add(providerKey)) {

                    // update the locations map for this provider
                    locations[providerKey] = loc

                    // if we've gotten an update from all the providers then
                    //  stop the timeout handler and process the results
                    if (receivedProviders.containsAll(availableProviders)) {
                        stopTimeoutHandler()
                        processCurrentLocationResults()
                    }
                } else {

                    // this should never happen but just in case...
                    if (locations[providerKey] == null) {

                        // if its null then use the new location
                        locations[providerKey] = loc

                    } else {

                        // if we already have a location from this provider then compare them and
                        //  use the most recent one
                        if (loc.time > locations[providerKey]!!.time) {
                            Log.d(
                                "processProviderLocRes", "New location is more recent," +
                                        " updating location for provider $providerKey from " +
                                        "${locations[providerKey]!!.time} to ${loc.time}"
                            )
                            locations[providerKey] = loc
                        }
                    }
                }
            }
        }

        // called either when we have all locations or when we time out
        // this is equivalent to the processLocationResult() in the google play LocationHelper
        private fun processCurrentLocationResults() {
            Log.d("processCurLocResults", "Processing ${locations.size} locations")

            // get the location with the best accuracy and most recent timestamp
            // docs say not to compare time between location providers because they aren't
            //  guaranteed to be using the same clock?!
            val bestLoc =
                locations.values.minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
            Log.d("processCurLocResults", "Best location: $bestLoc")

            // This block will be executed whether the task was successful or not
            if (bestLoc != null) {

                // it doesn't matter where we get the location from, if we get one then we can
                //  geocode it and send it to the callback
                Log.d(
                    "processCurLocResults",
                    "Location from ${bestLoc.provider} is (${bestLoc.latitude}, ${bestLoc.longitude}) " +
                            "${bestLoc.accuracy}acc at ${getDateTimeStrFromTimestamp(bestLoc.time)}"
                )

                // try to geocode the location and then execute the callback
                GeocodingHelper().geocodeLocationAndExecute(bestLoc)

            } else {

                // if the current location is null then try to get the last location
                DebugLogger.d("processCurrentLocationResult", "Unable to get current location")
                getLastLocation()
            }
        }

        // this will get called if we time out while trying to get the location(s) from each provider
        private val timeoutRunnable = Runnable {

            DebugLogger.d("LocationComparator", "Timeout reached")

            // this is only used with requestLocationUpdates but we can cancel it anyway?
            cancellationSignal.cancel()

            // this is only used with requestLocationUpdatesOld but we can do it anyway?
            locationManager.removeUpdates(locationListener)

            // process the locations we have so far (if any)
            processCurrentLocationResults()
        }

        private fun startTimeoutHandler() {
            timeoutHandler.postDelayed(timeoutRunnable, locationRequestTimeoutLength)
        }

        private fun stopTimeoutHandler() {
            timeoutHandler.removeCallbacks(timeoutRunnable)
        }
    }
}