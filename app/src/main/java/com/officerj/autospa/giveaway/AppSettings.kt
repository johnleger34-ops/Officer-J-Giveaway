package com.officerj.autospa.giveaway

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("officer_j_settings", Context.MODE_PRIVATE)

    var updateManifestUrl: String
        get() = prefs.getString("update_manifest_url", "") ?: ""
        set(value) = prefs.edit().putString("update_manifest_url", value).apply()

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("auto_check_updates", false)
        set(value) = prefs.edit().putBoolean("auto_check_updates", value).apply()
}
