package com.officerj.autospa.giveaway

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("officer_j_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "Meta Direct") ?: "Meta Direct"
        set(value) = prefs.edit().putString("provider", value).apply()

    var metaApiVersion: String
        get() = prefs.getString("meta_api_version", "v26.0") ?: "v26.0"
        set(value) = prefs.edit().putString("meta_api_version", value).apply()

    var userAccessToken: String
        get() = prefs.getString("user_access_token", DEFAULT_USER_ACCESS_TOKEN).orEmpty().ifBlank { DEFAULT_USER_ACCESS_TOKEN }
        set(value) = prefs.edit().putString("user_access_token", value).apply()

    var pageAccessToken: String
        get() {
            val explicit = prefs.getString("page_access_token", null).orEmpty()
            if (explicit.isNotBlank()) return explicit
            // v1.1.7 had one generic token field. If the user replaced it with the Page token,
            // preserve that value during upgrade; otherwise use the embedded Page-token default.
            val legacy = prefs.getString("access_token", "").orEmpty()
            return if (legacy.isNotBlank() && legacy != DEFAULT_USER_ACCESS_TOKEN) legacy else DEFAULT_PAGE_ACCESS_TOKEN
        }
        set(value) = prefs.edit().putString("page_access_token", value).apply()

    @Deprecated("Use userAccessToken/pageAccessToken")
    var accessToken: String
        get() = userAccessToken
        set(value) { userAccessToken = value }

    var pageId: String
        get() {
            val stored = prefs.getString("page_id", DEFAULT_PAGE_ID).orEmpty().ifBlank { DEFAULT_PAGE_ID }
            // Migrate the early test build's one-digit-short Page ID automatically.
            return if (stored == LEGACY_BAD_PAGE_ID) DEFAULT_PAGE_ID else stored
        }
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

    companion object {
        // User-requested embedded defaults. Settings can still override either value in-app.
        const val LEGACY_BAD_PAGE_ID = "88834713103428"
        const val DEFAULT_PAGE_ID = "888347131034286"
        const val DEFAULT_USER_ACCESS_TOKEN = "EAAZAGj6PnHQMBSLBbwuYJpjC5eLdkBk6ROG9ZCKua9OrOIDRh7Vxij3dlAxtv5rRkyLRFlkfkncGuyJbMeVuLZAlSBwkkXAFZAJWGXWMWFVhwhEKGUrZAOb5tFJh54TDYN6Ge70HzkH24QZBJsi3R3sPIRZBDkeMZAm5ZCXQJ4B0IkbjoyF7zwWrwxgCB3Cv4Qt8ELC8YGXklHCZAaMLnxZCbZBZA27cP8qqoq404Y9vZBHtHgYPJHbwY9Sb6sXgZDZD"
        const val DEFAULT_PAGE_ACCESS_TOKEN = "EAAZAGj6PnHQMBSAIDIWPHpREuSXAmZAnOAasKv7RfkXnqXfv9P765iZC3S4oZApAYtg3OewSiRjNBlZCyeijNrjuU9VfmNQNoFCUJaIS3XaqUoo33tdXZAB63i7UDyPcZBgfc74byOX1O3k2DUhS8ZCI30jxIllZBtTkn7XGZCPTlVlIJbZAWlpNAfMuySSZC3jjO6Q73OYSy1MhOMllk9tphZC45xR9dVUTzlgwyB1gtGTrdXeYZD"
    }
}
