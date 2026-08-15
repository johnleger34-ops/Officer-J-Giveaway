package com.officerj.autospa.giveaway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GiveawayType { WHEEL, RAFFLE }

data class EntryGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var quantity: Int,
    var bonusWeight: Double = 0.0
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("quantity", quantity).put("bonusWeight", bonusWeight)
    companion object {
        fun fromJson(o: JSONObject) = EntryGroup(o.optString("id", UUID.randomUUID().toString()), o.optString("name"), o.optInt("quantity", 1).coerceAtLeast(1), o.optDouble("bonusWeight", 0.0).coerceAtLeast(0.0))
    }
}

data class WinnerResult(
    val name: String,
    val ticketNumber: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().put("name", name).put("ticketNumber", ticketNumber).put("timestamp", timestamp)
    companion object {
        fun fromJson(o: JSONObject) = WinnerResult(o.optString("name"), o.optInt("ticketNumber"), o.optLong("timestamp"))
    }
}

data class Giveaway(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val type: GiveawayType,
    val entries: MutableList<EntryGroup> = mutableListOf(),
    val winners: MutableList<WinnerResult> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis()
) {
    val totalEntries: Int get() = entries.sumOf { it.quantity }

    fun expandedTickets(): List<Pair<Int, String>> {
        var n = 1
        val out = ArrayList<Pair<Int, String>>(totalEntries)
        entries.forEach { group ->
            repeat(group.quantity) { out += n++ to group.name }
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("type", type.name)
        .put("createdAt", createdAt)
        .put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        .put("winners", JSONArray().apply { winners.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Giveaway {
            val g = Giveaway(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", "Untitled Giveaway"),
                type = runCatching { GiveawayType.valueOf(o.optString("type")) }.getOrDefault(GiveawayType.WHEEL),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
            val ea = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until ea.length()) g.entries += EntryGroup.fromJson(ea.getJSONObject(i))
            val wa = o.optJSONArray("winners") ?: JSONArray()
            for (i in 0 until wa.length()) g.winners += WinnerResult.fromJson(wa.getJSONObject(i))
            return g
        }
    }
}
