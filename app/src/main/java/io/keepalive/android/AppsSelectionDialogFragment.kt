package io.keepalive.android

import android.Manifest
import android.app.Dialog
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.gson.Gson
import java.text.DateFormat
import java.util.Date

class AppsSelectionDialogFragment(val callback: () -> Unit) : DialogFragment() {

    private lateinit var availableAppsListView: ListView
    private lateinit var chosenAppsListView: ListView
    private var availableApps = mutableListOf<MonitoredAppDetails>()
    private var chosenApps = mutableListOf<MonitoredAppDetails>()

    class AppDetailsAdapter(context: Context, items: List<MonitoredAppDetails>,
                         private val displayMode: String,
                         private val onShowDetailsClick: (Context, MonitoredAppDetails) -> Unit? = { _, _ -> }) :
        ArrayAdapter<MonitoredAppDetails>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_app, parent, false)

            val appNameTextView = view.findViewById<TextView>(R.id.appNameTextView)
            val viewDetailsTextView = view.findViewById<TextView>(R.id.viewDetailsTextView)

            val appInfo = getItem(position)

            // control what is displayed based on the display mode so we can use the same
            //  adapter for everything
            if (displayMode == "details") {
                appNameTextView.text = appInfo?.appName
                viewDetailsTextView.text = context.getString(R.string.monitored_apps_view_history_text)

                // set the text to bold and underlined to make it more obvious that it is clickable
                viewDetailsTextView.paintFlags = viewDetailsTextView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                viewDetailsTextView.setTypeface(null, Typeface.BOLD)

                viewDetailsTextView.setOnClickListener {
                    onShowDetailsClick(context, appInfo!!)
                }
            } else {
                // for easier readability, remove the package name from the class name
                //appNameTextView.text = appInfo?.className?.replace(appInfo.packageName + ".", "")
                appNameTextView.text = appInfo?.className

                // format the datetime
                viewDetailsTextView.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(
                    Date(appInfo?.lastUsed ?: 0)
                )
            }
            return view
        }
    }

    private fun showDetailsDialog(context: Context, appInfo: MonitoredAppDetails) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_apps_show_details, null)

        // get app usage details for the last 72 hours
        val appUsageDetails = getAppUsageDetails(context,
            System.currentTimeMillis() - 1000L * 60 * 60 * 72, appInfo.packageName)

        // populate the list view on the dialog layout with the app usage details
        val appDetailsListView: ListView = dialogView.findViewById(R.id.appDetailsListView)
        appDetailsListView.adapter = AppDetailsAdapter(
            context,
            appUsageDetails.sortedByDescending { it.lastUsed },
            "history"
        )

        AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(context.getString(R.string.monitored_apps_view_history_title, appInfo.appName))
            .setView(dialogView)
            .setPositiveButton(context.getString(R.string.close), null)
            .show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_apps_selection, null)
        val prefs = getEncryptedSharedPreferences(requireContext())

        // get the list views on the dialog layout
        availableAppsListView = view.findViewById(R.id.availableAppsListView)
        chosenAppsListView = view.findViewById(R.id.chosenAppsListView)

        // update the chosen app list with what the user has configured
        val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(prefs,"APPS_TO_MONITOR")
        chosenApps.addAll(appsToMonitor.sortedBy { it.appName })

        // update the available app list
        val availableAppDetails = getAppUsageDetails(requireContext(), System.currentTimeMillis() - 1000L * 60 * 60 * 72)
        availableApps.clear()
        availableApps.addAll(availableAppDetails)

        // get the adapters that will be used to display the lists
        val availableAppsAdapter = AppDetailsAdapter(requireContext(), availableApps, "details", ::showDetailsDialog)
        val chosenAppsAdapter = AppDetailsAdapter(requireContext(), chosenApps, "details", ::showDetailsDialog)
        availableAppsListView.adapter = availableAppsAdapter
        chosenAppsListView.adapter = chosenAppsAdapter

        setListViewHeightBasedOnItemCount(chosenAppsListView)

        // add listeners for when a list item is clicked on
        availableAppsListView.setOnItemClickListener { _, _, position, _ ->

            // move the app from the available list to the chosen list
            val app = availableApps.removeAt(position)
            chosenApps.add(app)

            // update the chosenAppsListView height, the availableAppsListView will
            //  auto expand to fill the remaining space
            setListViewHeightBasedOnItemCount(chosenAppsListView)

            // display chosen apps in alphabetical order and update the list display
            chosenApps.sortBy { it.appName }
            availableAppsAdapter.notifyDataSetChanged()
            chosenAppsAdapter.notifyDataSetChanged()
        }

        chosenAppsListView.setOnItemClickListener { _, _, position, _ ->

            // remove the app from the chosen list and refresh the available app list
            chosenApps.removeAt(position)

            // since the lastUsedTime is saved when an app is chosen, if the user is later
            //  reconfiguring the apps, it may have an old lastUsedTime that is no longer accurate
            //  so instead just refresh the list of available apps
            availableApps.clear()

            // display available apps with the most recently used first
            val thisAvailableAppDetails = getAppUsageDetails(requireContext(), System.currentTimeMillis() - 1000L * 60 * 60 * 72)
            availableApps.clear()
            availableApps.addAll(thisAvailableAppDetails)

            // update the list view height
            setListViewHeightBasedOnItemCount(chosenAppsListView)

            // update the list display
            availableAppsAdapter.notifyDataSetChanged()
            chosenAppsAdapter.notifyDataSetChanged()
        }

        return AlertDialog.Builder(requireActivity(), R.style.AlertDialogTheme)
            .setView(view)
            .setTitle(getString(R.string.monitored_apps_dialog_title))

            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->

                if (chosenApps.isEmpty()) {
                    // if the user saved the monitoring settings without any apps selected and they
                    //  aren't able to use device lock/unlock events then also disable monitoring
                    if (Build.VERSION.SDK_INT < AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {
                        with(prefs.edit()) {
                            putBoolean("enabled", false)
                            apply()
                        }
                    }
                }

                // Save the chosen apps
                saveChosenApps(requireContext(), chosenApps)
                callback()
            }
            // allow the user to delete all the chosen apps
            .setNeutralButton(getString(R.string.delete)) { _, _ ->
                chosenApps.clear()
                saveChosenApps(requireContext(), chosenApps)

                // if the user deleted the monitoring settings and they aren't able to use
                //  device lock/unlock events then also disable monitoring
                if (Build.VERSION.SDK_INT < AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {
                    with(prefs.edit()) {
                        putBoolean("enabled", false)
                        apply()
                    }
                }

                callback()
            }
            .create()
    }

    private fun setListViewHeightBasedOnItemCount(listView: ListView, maxItems: Int = 5) {
        val listAdapter = listView.adapter ?: return

        // calculate the height of the list view based on the number of items in the list
        // limit the height to maxItems
        var totalHeight = 0
        for (i in 0 until minOf(listAdapter.count, maxItems)) {
            val listItem = listAdapter.getView(i, null, listView)
            listItem.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            totalHeight += listItem.measuredHeight
        }

        Log.d("AppsSelectionDialogFrag", "Total height: $totalHeight for ${listAdapter.count} items in listview $listView")

        val params = listView.layoutParams
        params.height = totalHeight + listView.dividerHeight * (listAdapter.count - 1)
        listView.layoutParams = params
        listView.requestLayout()
    }

    private fun saveChosenApps(context: Context, appInfos: MutableList<MonitoredAppDetails>) {

        val prefs = getEncryptedSharedPreferences(context)

        with(prefs.edit()) {

            // convert the list to json and save it to shared prefs
            val jsonString = Gson().toJson(appInfos)
            putString("APPS_TO_MONITOR", jsonString)
            apply()
        }
    }

    private fun getAppUsageDetails(context: Context, startTimestamp: Long,
                                    targetPackageName: String? = null): List<MonitoredAppDetails> {

        // get the package names of the chosen apps so we don't include them
        val savedChosenPackageNames = chosenApps.map { it.packageName }.toSet()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTime = System.currentTimeMillis()

        // Adjust the time range according to your needs
        val usageEvents = usageStatsManager.queryEvents(startTimestamp, currentTime)

        val appInfoList = mutableListOf<MonitoredAppDetails>()
        val pm = context.packageManager

        // the events we are looking for
        val targetEvents = mutableListOf(UsageEvents.Event.MOVE_TO_FOREGROUND)

        // when testing under API 34 this never seems to fire, apps still sending MOVE_TO_FOREGROUND
        // if it is available we should still check it though
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            targetEvents.add(UsageEvents.Event.ACTIVITY_RESUMED)
        }

        // assume we have the permission to query all packages but if we are on API 30 or higher
        //  we need to check for the QUERY_ALL_PACKAGES permission
        var haveQueryAllPackagesPermission = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            haveQueryAllPackagesPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.QUERY_ALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        }

        Log.d("AppsSelectionDialogFrag", "Can query package names: $haveQueryAllPackagesPermission")

        // loop through the events
        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)

            // look for the target events and if we are filtering for a specific package, only
            //  add events for that package
            if (event.eventType in targetEvents && (targetPackageName == null || event.packageName == targetPackageName)) {

                // default to using the package name as the app name, will get updated to
                //  the friendly name if possible
                var appName = event.packageName
                val lastTimeUsed = event.timeStamp

                try {
                    // ignore our own package
                    if (event.packageName == context.packageName) {
                        continue
                    }

                    // if we are allowed to query packages we can get the apps display name
                    if (haveQueryAllPackagesPermission) {

                        // in API 33 they added a different way to get the application info
                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                            pm.getApplicationInfo(
                                event.packageName,
                                PackageManager.ApplicationInfoFlags.of(0)
                            )

                        } else {
                            pm.getApplicationInfo(
                                event.packageName,
                                PackageManager.GET_META_DATA
                            )

                        }
                        appName = pm.getApplicationLabel(appInfo).toString()
                    }

                    // round down to the nearest minute to reduce the number of entries
                    val roundedLastTimeUsed = lastTimeUsed / 60000 * 60000

                    // we want to make the list unique by app name and when it was last used
                    val existingAppInfo = appInfoList.find { it.packageName == event.packageName && it.lastUsed == roundedLastTimeUsed }
                    if (existingAppInfo != null) {

                        // App exists, check if the new event is more recent
                        if (roundedLastTimeUsed > existingAppInfo.lastUsed) {

                            // New event is more recent, update the existing entry
                            appInfoList[appInfoList.indexOf(existingAppInfo)] = MonitoredAppDetails(event.packageName, appName, roundedLastTimeUsed, event.className)
                        }

                        // if we are looking for a specific package we can ignore the chosen name check
                    } else if (event.packageName !in savedChosenPackageNames || targetPackageName != null) {

                        // App does not exist in the temp list and is not a chosen app, add it
                        appInfoList.add(MonitoredAppDetails(event.packageName, appName, roundedLastTimeUsed, event.className))
                    }

                } catch (e: Exception) {
                    Log.e("AppsSelectionDialogFrag", "Error getting app info for $appName", e)
                }
            }
        }

        // if we weren't filtering for a specific package, make the list distinct by app name
        if (targetPackageName == null) {
            return appInfoList.distinctBy { it.appName }.sortedByDescending { it.appName }
        }

        return appInfoList
    }
}
