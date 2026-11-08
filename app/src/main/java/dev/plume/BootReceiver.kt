package dev.plume

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Android 13+ drops a sideloaded accessibility service from the enabled list on a cold boot (the
 * "restricted settings" re-lock), which turns off Plume's Home service and uncovers Google TV's
 * launcher. With WRITE_SECURE_SETTINGS granted once over adb we turn the service back on here, so
 * Plume keeps taking over after every power cycle without any manual step. The service then covers
 * the launcher on connect (see HomeOverride.onServiceConnected).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
            val me = ComponentName(context, HomeOverride::class.java)
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (on.split(':').none { ComponentName.unflattenFromString(it) == me }) {
                Settings.Secure.putString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (on.isEmpty()) me.flattenToString() else "$on:${me.flattenToString()}"
                )
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            // onUnbind set this when Android dropped the service; clear it so the override acts again.
            context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("home_off").apply()
        } catch (e: Exception) {
            // best effort; nothing we can do without the permission
        }
    }
}
