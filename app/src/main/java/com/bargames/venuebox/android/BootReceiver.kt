package com.bargames.venuebox.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Power-on: go straight back to the last box, so a venue TV recovers from a power cut.
 * Android 10+ only lets an app open its screen from the background when it may "display over
 * other apps" (SYSTEM_ALERT_WINDOW); without it the connection still starts, and the screen
 * shows when someone opens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val ctl = context.controller
        if (ctl.config.box == null) {
            Log.i(TAG, "boot: no box set yet, not starting")
            return
        }
        Log.i(TAG, "boot: starting")
        ctl.start()
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "boot: couldn't open the screen: ${e.message}")
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // some TV sticks
        )
    }
}
