package com.officerj.autospa.giveaway

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("officer_j_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "Meta Direct") ?: "Meta Direct"
        set(value) = prefs.edit().putString("provider", value).apply()

    var metaApiVersion: String
        get() = prefs.getString("meta_api_version", "v23.0") ?: "v23.0"
        set(value) = prefs.edit().putString("meta_api_version", value).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var pageId: String
        get() = prefs.getString("page_id", "") ?: ""
        set(value) = prefs.edit().putString("page_id", value).apply()

    var defaultPostUrl: String
        get() = prefs.getString("default_post_url", "") ?: ""
        set(value) = prefs.edit().putString("default_post_url", value).apply()

    var minimumVerified: Int
        get() = prefs.getInt("minimum_verified", 2).coerceIn(1, 3)
        set(value) = prefs.edit().putInt("minimum_verified", value.coerceIn(1, 3)).apply()

    var standardWeight: Double
        get() = prefs.getString("standard_weight", "1.00")?.toDoubleOrNull() ?: 1.0
        set(value) = prefs.edit().putString("standard_weight", value.coerceAtLeast(0.0).toString()).apply()

    var bonusWeight: Double
        get() = prefs.getString("bonus_weight", "1.15")?.toDoubleOrNull() ?: 1.15
        set(value) = prefs.edit().putString("bonus_weight", value.coerceAtLeast(0.0).toString()).apply()

    var unknownBehavior: String
        get() = prefs.getString("unknown_behavior", "Needs Review") ?: "Needs Review"
        set(value) = prefs.edit().putString("unknown_behavior", value).apply()

    var updateManifestUrl: String
        get() = prefs.getString("update_manifest_url", "") ?: ""
        set(value) = prefs.edit().putString("update_manifest_url", value).apply()

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("auto_check_updates", false)
        set(value) = prefs.edit().putBoolean("auto_check_updates", value).apply()
}
