package io.keepalive.android

import android.content.SharedPreferences
import android.telephony.PhoneNumberUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.util.Locale


// adapter for the recycler view that displays the list of SMS contact numbers
class PhoneNumberAdapter(
    private val phoneNumberList: MutableList<SMSEmergencyContactSetting>,
    private val sharedPrefs: SharedPreferences,
    private val onEdit: (position: Int) -> Unit
) : RecyclerView.Adapter<PhoneNumberAdapter.ViewHolder>() {

    private val gson = Gson()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val phoneNumberText: TextView = itemView.findViewById(R.id.phoneNumberText)
        val alertMessageText: TextView = itemView.findViewById(R.id.alertMessageText)
        val enabledSwitch: SwitchCompat = itemView.findViewById(R.id.enabledSwitch)
        val locationSwitch: SwitchCompat = itemView.findViewById(R.id.locationSwitch)
        var isInitializing = true

        init {

            // set edit button click listener on the entire view
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEdit(pos)
                }
            }

            // if the user changes the enabled switch from the main screen, save the settings
            enabledSwitch.setOnCheckedChangeListener { _, _ ->
                if (!isInitializing) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        phoneNumberList[pos].isEnabled = enabledSwitch.isChecked
                        saveSMSEmergencyContactSettings(sharedPrefs, phoneNumberList, gson)
                    }
                }
            }

            // if the user changes the location switch from the main screen, save the settings
            locationSwitch.setOnCheckedChangeListener { _, _ ->
                if (!isInitializing) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        phoneNumberList[pos].includeLocation = locationSwitch.isChecked
                        saveSMSEmergencyContactSettings(sharedPrefs, phoneNumberList, gson)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phone_number_row, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentItem = phoneNumberList[position]

        holder.isInitializing = true

        // format the phone number for display
        holder.phoneNumberText.text = PhoneNumberUtils.formatNumber(
            currentItem.phoneNumber,
            Locale.getDefault().country
        )
        holder.alertMessageText.text = currentItem.alertMessage
        holder.enabledSwitch.isChecked = currentItem.isEnabled
        holder.locationSwitch.isChecked = currentItem.includeLocation

        // set the content description dynamically based on which # in the list it is
        //  to better help accessibility readers?
        holder.enabledSwitch.contentDescription = String.format(
            holder.itemView.context.getString(
                R.string.enable_switch_enabled_content_desc
            ),
            position.toString()
        )

        holder.locationSwitch.contentDescription = String.format(
            holder.itemView.context.getString(
                R.string.location_switch_enabled_content_desc
            ),
            position.toString()
        )

        holder.isInitializing = false
    }

    override fun getItemCount() = phoneNumberList.size

    fun addPhoneNumber(setting: SMSEmergencyContactSetting) {
        phoneNumberList.add(setting)
        notifyItemInserted(phoneNumberList.size - 1)
    }

    fun editPhoneNumber(position: Int, setting: SMSEmergencyContactSetting) {
        phoneNumberList[position] = setting
        notifyItemChanged(position)
    }

    fun deletePhoneNumber(position: Int) {
        phoneNumberList.removeAt(position)
        notifyItemRemoved(position)
    }
}
    