package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.keepalive.android.AreYouThereRepost

class AreYouThereRepostReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AreYouThereRepost.repost(context)
    }
}
