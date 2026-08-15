package com.officerj.autospa.giveaway

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class MetaEngagementClient(private val settings: AppSettings) {
    data class ScanResult(
        val postId: String,
        val reactions: Map<String, String>,
        val comments: Map<String, String>,
        val reactionStatus: String,
        val commentStatus: String,
        val followerStatus: String
    )

    fun scan(postUrl: String): ScanResult {
        val token = settings.accessToken.trim()
        if (token.isBlank()) return ScanResult("", emptyMap(), emptyMap(), "Access token missing", "Access token missing", "Unavailable")
        val postId = resolvePostId(postUrl, token)
        if (postId.isBlank()) return ScanResult("", emptyMap(), emptyMap(), "Could not resolve post", "Could not resolve post", "Unavailable")

        val reactions = linkedMapOf<String, String>()
        val comments = linkedMapOf<String, String>()
        val reactionStatus = runCatching {
            readPaged("$postId/reactions", token, "id,name") { o ->
                val id = o.optString("id"); val name = o.optString("name")
                if (id.isNotBlank()) reactions[id] = name.ifBlank { id }
            }
            "Loaded ${reactions.size}"
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        val commentStatus = runCatching {
            readPaged("$postId/comments", token, "from,message") { o ->
                val from = o.optJSONObject("from")
                val id = from?.optString("id").orEmpty(); val name = from?.optString("name").orEmpty()
                if (id.isNotBlank()) comments[id] = name.ifBlank { id }
            }
            "Loaded ${comments.size}"
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        return ScanResult(postId, reactions, comments, reactionStatus, commentStatus,
            "Individual follower lookup is not exposed reliably; manual verification remains available")
    }

    private fun resolvePostId(postUrl: String, token: String): String {
        val raw = postUrl.trim()
        if (raw.matches(Regex("^[0-9_]+$"))) return raw
        val story = Regex("[?&]story_fbid=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        val owner = Regex("[?&]id=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!story.isNullOrBlank() && !owner.isNullOrBlank()) return "${owner}_${story}"
        val posts = Regex("/(?:posts|videos)/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!posts.isNullOrBlank() && settings.pageId.isNotBlank()) return "${settings.pageId}_$posts"

        // Best-effort Graph lookup. Personal-profile URLs frequently cannot be resolved by modern Meta APIs.
        return runCatching {
            val encoded = URLEncoder.encode(raw, "UTF-8")
            val obj = getJson("?id=$encoded", token)
            obj.optString("id")
        }.getOrDefault("")
    }

    private fun readPaged(path: String, token: String, fields: String, consume: (JSONObject) -> Unit) {
        var next: String? = graphUrl("$path?limit=100&fields=${URLEncoder.encode(fields, "UTF-8")}") + "&access_token=${URLEncoder.encode(token, "UTF-8")}"
        var pages = 0
        while (!next.isNullOrBlank() && pages++ < 100) {
            val root = getJsonAbsolute(next)
            val error = root.optJSONObject("error")
            if (error != null) throw IllegalStateException(error.optString("message", "Meta API error"))
            val data = root.optJSONArray("data")
            if (data != null) for (i in 0 until data.length()) consume(data.getJSONObject(i))
            next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
        }
    }

    private fun getJson(path: String, token: String): JSONObject {
        val joiner = if (path.contains("?")) "&" else "?"
        return getJsonAbsolute(graphUrl(path) + joiner + "access_token=${URLEncoder.encode(token, "UTF-8")}")
    }

    private fun graphUrl(path: String): String {
        val p = if (path.startsWith("/")) path.substring(1) else path
        return "https://graph.facebook.com/${settings.metaApiVersion.trim().ifBlank { "v23.0" }}/$p"
    }

    private fun getJsonAbsolute(urlString: String): JSONObject {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 18000; conn.requestMethod = "GET"
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        val obj = JSONObject(text.ifBlank { "{}" })
        if (code !in 200..299) {
            val msg = obj.optJSONObject("error")?.optString("message") ?: "HTTP $code"
            throw IllegalStateException(msg)
        }
        return obj
    }

    private fun cleanError(value: String?) = value.orEmpty().replace('\n', ' ').take(120).ifBlank { "Unknown error" }
}
