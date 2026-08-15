package com.officerj.autospa.giveaway

import android.content.Context
import org.json.JSONObject
import java.io.File

class EngagementStore(context: Context) {
    private val file = File(context.filesDir, "officer_j_engagement.json")

    fun load(): MutableMap<String, EngagementScan> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        val root = JSONObject(file.readText())
        val out = mutableMapOf<String, EngagementScan>()
        root.keys().forEach { id -> out[id] = EngagementScan.fromJson(root.getJSONObject(id)) }
        out
    }.getOrElse { mutableMapOf() }

    fun save(items: Map<String, EngagementScan>) {
        val root = JSONObject()
        items.forEach { (id, scan) -> root.put(id, scan.toJson()) }
        file.writeText(root.toString(2))
    }
}
