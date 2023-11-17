# Keep Alive
Keep Alive will send a custom message via SMS to one or more people if you haven't used your device in a given period of time. 
Intended to be used as a failsafe for those living alone in case of an accident or other emergency.
Once the settings are configured, no further interaction is required.

[![Google Play](http://developer.android.com/images/brand/en_generic_rgb_wo_45.png)](https://play.google.com/store/apps/details?id=io.keepalive.android) 

## How it Works
  Keep Alive uses your device's lock screen to detect activity.
  If your device hasn't been locked or unlocked within a set period of time, you will be prompted with an 'Are you there?' notification.
  If this notification is not acknowledged an Alert will be triggered. Based on the configured Emergency Contact Settings, 
  one or more SMS messages and/or a phone call will be placed to notify others that you may be in need of assistance.
  If enabled, a second SMS will be sent with location information to aid others in locating you.

## Features
- 100% Device-based, no cloud services or accounts required
- Free with no ads or trackers
- Minimal Battery Usage
- Multiple SMS Recipients
- Custom Alert Messages
- Optional: Include Location Information in SMS
- Optional: Place a phone call with speakerphone enabled

## Requirements
  - **Lock Screen** - used to detect when the device was last used
  - **Active SIM** - used to send SMS and place phone calls
    - WiFi calling and messaging will be used if the device supports it

## Main Settings
- **Hours of Inactivity Before Prompt** 
   - How many hours since your phone was last locked or unlocked before you are prompted with an 'Are you there?' notification
   - May be delayed up to an hour by the OS if the device has low power or is in DnD mode
   - Default is 12 hours
- **Minutes to Wait**
  - If the 'Are you there?' prompt is not acknowledged within this time, an Alert will be sent
  - Will not be delayed
  - Default is 60 minutes

## Emergency Contact Settings
- **SMS Contact(s)**:
  - **Phone Number**: the phone number to send the Alert SMS to
  - **Alert Message**: the message that will be sent when an Alert is triggered
  - **Include Location**: if enabled, your location will be included in a second SMS
- **Phone Call (Optional)**: 
  - **Phone Number**: when an Alert is triggered a phone call will be placed to this number with speakerphone enabled

## Permissions

Keep Alive requires the following permissions to operate properly:

* **Usage Stats**: Used to determine when your phone was last used by checking for lock and unlock events
* **Schedule Exact Alarms**: Needed to ensure that we can set alarms that go off even when the device is idle or in Do-Not-Disturb mode
* **Send SMS**: Used to send SMS messages to your emergency contact(s)
* **Send Notifications**: Used to display the 'Are you there?' and 'Alert triggered' notifications

### Optional Permissions
If Include Location is enabled:
* **Precise Location**: Necessary so that we have the most accurate location information to share with your emergency contacts
* **Background Location**: Necessary to be able to get location information when the device is idle

If a Phone Call number is configured:
* **Make and Manage Calls**: Basic permission to make phone calls
* **Manage Overlays**: Necessary to be able to make phone calls when the device is idle


## App Restrictions / App Hibernation 
Android now automatically removes permissions for apps that have not been used recently. 
Keep Alive, once the settings are configured, does not need any further interaction and may have its 
permissions revoked, possibly preventing it from sending an Alert. 
Keep Alive was designed to operate as unobtrusively as possible and, as an alternative to bugging the user to
open the app periodically, expects this behavior to be disabled. On the most recent version of Android this is called 
'Pause app activity if unused' and can be found on the App Info screen for Keep Alive. 


## Why?
Keep Alive was created after separate incidents with colleagues who lived alone and lost consciousness at home.
One was found within 24 hours by a concerned friend doing a welfare check and was saved, 
albeit not unscathed. The other was not discovered for more than 36 hours and sadly succumbed to his 
injuries after several days in the ICU. If they had been found even a few hours sooner, 
things may have turned out differently.

## Disclaimer
- Not responsible for SMS or phone call charges incurred by the use of the Keep Alive app
- Keep Alive is not a substitute for professional healthcare or emergency services. Users should always seek
professional advice in situations that require medical attention or emergency services.
- The operation of the Keep Alive app is dependent on the device, software, and network connectivity. 
The developers are not responsible for any failure due to device malfunctions, software incompatibilities, or network issues.

