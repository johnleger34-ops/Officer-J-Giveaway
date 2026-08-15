package com.officerj.autospa.giveaway

import org.json.JSONArray
import org.json.JSONObject

enum class VerificationState { VERIFIED, NOT_FOUND, UNKNOWN }

data class EngagementParticipant(
    val id: String,
    var name: String,
    var reacted: VerificationState = VerificationState.UNKNOWN,
    var commented: VerificationState = VerificationState.UNKNOWN,
    var followsPage: VerificationState = VerificationState.UNKNOWN,
    var source: String = "Meta",
    var updatedAt: Long = System.currentTimeMillis()
) {
    val verifiedCount: Int
        get() = listOf(reacted, commented, followsPage).count { it == VerificationState.VERIFIED }

    fun eligibility(settings: AppSettings): Eligibility {
        if (verifiedCount < settings.minimumVerified) return Eligibility.NOT_ELIGIBLE
        if (verifiedCount == 3) return Eligibility.BONUS
        return if (listOf(reacted, commented, followsPage).any { it == VerificationState.UNKNOWN } && settings.unknownBehavior == "Needs Review") {
            Eligibility.STANDARD_REVIEW
        } else Eligibility.STANDARD
    }

    fun weight(settings: AppSettings): Double = when (eligibility(settings)) {
        Eligibility.BONUS -> settings.bonusWeight
        Eligibility.STANDARD, Eligibility.STANDARD_REVIEW -> settings.standardWeight
        Eligibility.NOT_ELIGIBLE -> 0.0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("reacted", reacted.name).put("commented", commented.name).put("followsPage", followsPage.name)
        .put("source", source).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = EngagementParticipant(
            id = o.optString("id"), name = o.optString("name"),
            reacted = enumOrUnknown(o.optString("reacted")),
            commented = enumOrUnknown(o.optString("commented")),
            followsPage = enumOrUnknown(o.optString("followsPage")),
            source = o.optString("source", "Meta"), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )

        private fun enumOrUnknown(value: String) = runCatching { VerificationState.valueOf(value) }.getOrDefault(VerificationState.UNKNOWN)
    }
}

enum class Eligibility { NOT_ELIGIBLE, STANDARD, STANDARD_REVIEW, BONUS }

data class EngagementScan(
    var postUrl: String = "",
    var postObjectId: String = "",
    val participants: MutableList<EngagementParticipant> = mutableListOf(),
    var reactionStatus: String = "Not scanned",
    var commentStatus: String = "Not scanned",
    var followerStatus: String = "Not scanned",
    var lastScan: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("postUrl", postUrl).put("postObjectId", postObjectId)
        .put("reactionStatus", reactionStatus).put("commentStatus", commentStatus).put("followerStatus", followerStatus)
        .put("lastScan", lastScan)
        .put("participants", JSONArray().apply { participants.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): EngagementScan {
            val scan = EngagementScan(
                postUrl = o.optString("postUrl"), postObjectId = o.optString("postObjectId"),
                reactionStatus = o.optString("reactionStatus", "Not scanned"),
                commentStatus = o.optString("commentStatus", "Not scanned"),
                followerStatus = o.optString("followerStatus", "Not scanned"),
                lastScan = o.optLong("lastScan")
            )
            val a = o.optJSONArray("participants") ?: JSONArray()
            for (i in 0 until a.length()) scan.participants += EngagementParticipant.fromJson(a.getJSONObject(i))
            return scan
        }
    }
}
