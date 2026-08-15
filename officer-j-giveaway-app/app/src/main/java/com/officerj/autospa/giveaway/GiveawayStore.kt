package com.officerj.autospa.giveaway

import android.content.Context
import org.json.JSONArray
import java.io.File

class GiveawayStore(private val context: Context) {
    private val file = File(context.filesDir, "officer_j_giveaways.json")

    fun load(): MutableList<Giveaway> = runCatching {
        if (!file.exists()) return@runCatching mutableListOf()
        val a = JSONArray(file.readText())
        MutableList(a.length()) { i -> Giveaway.fromJson(a.getJSONObject(i)) }
    }.getOrElse { mutableListOf() }

    fun save(items: List<Giveaway>) {
        val a = JSONArray()
        items.forEach { a.put(it.toJson()) }
        file.writeText(a.toString(2))
    }
}
