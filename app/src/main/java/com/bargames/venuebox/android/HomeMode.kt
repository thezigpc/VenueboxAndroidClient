package com.bargames.venuebox.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Home-screen mode for a tablet that only shows games: the app becomes the device's home app,
 * so it's back after every reboot and on the home button, without "display over other apps".
 * Off by default: [HomeAlias] (manifest) is disabled until staff turn it on, and turning it off
 * hands the device back to its normal home screen.
 */
class HomeMode(private val ctx: Context) {
    private val alias = ComponentName(ctx.packageName, "com.bargames.venuebox.android.HomeAlias")
    private val pm get() = ctx.packageManager

    /** Offered as a home app (the alias is enabled). */
    val enabled: Boolean
        get() = pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Picked as the home app. */
    val isHome: Boolean
        get() = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == ctx.packageName

    fun set(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
    }

    /** Opens Android's "default home app" setting, else the home picker (Just once / Always). */
    fun choose(from: Context) {
        val tries = listOf(Intent(Settings.ACTION_HOME_SETTINGS), homeIntent())
        for (i in tries) {
            try {
                from.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
}
