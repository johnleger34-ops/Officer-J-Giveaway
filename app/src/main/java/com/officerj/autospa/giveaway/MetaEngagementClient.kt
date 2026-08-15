package com.officerj.autospa.giveaway

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL

class MetaEngagementClient(private val settings: AppSettings) {
    data class ScanResult(
        val postId: String,
        val resolvedUrl: String,
        val resolutionStatus: String,
        val reactions: Map<String, String>,
        val comments: Map<String, String>,
        val reactionStatus: String,
        val commentStatus: String,
        val followerStatus: String
    )

    private data class ResolvedPost(val id: String, val url: String, val status: String)

    fun scan(postUrl: String): ScanResult {
        val token = settings.accessToken.trim()
        if (token.isBlank()) return ScanResult(
            "", postUrl.trim(), "Waiting for Meta access token",
            emptyMap(), emptyMap(), "Access token missing", "Access token missing", "Unavailable"
        )

        val resolved = resolvePost(postUrl, token)
        if (resolved.id.isBlank()) return ScanResult(
            "", resolved.url, resolved.status,
            emptyMap(), emptyMap(), "Not scanned: ${resolved.status}", "Not scanned: ${resolved.status}", "Unavailable"
        )

        val reactions = linkedMapOf<String, String>()
        val comments = linkedMapOf<String, String>()
        val reactionStatus = runCatching {
            readPaged("${resolved.id}/reactions", token, "id,name") { o ->
                val id = o.optString("id"); val name = o.optString("name")
                if (id.isNotBlank()) reactions[id] = name.ifBlank { id }
            }
            "Loaded ${reactions.size}"
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        val commentStatus = runCatching {
            readPaged("${resolved.id}/comments", token, "from,message") { o ->
                val from = o.optJSONObject("from")
                val id = from?.optString("id").orEmpty(); val name = from?.optString("name").orEmpty()
                if (id.isNotBlank()) comments[id] = name.ifBlank { id }
            }
            "Loaded ${comments.size}"
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        return ScanResult(
            resolved.id,
            resolved.url,
            resolved.status,
            reactions,
            comments,
            reactionStatus,
            commentStatus,
            "Individual follower lookup is not exposed reliably; manual verification remains available"
        )
    }

    private fun resolvePost(postUrl: String, token: String): ResolvedPost {
        val raw = postUrl.trim()
        if (raw.isBlank()) return ResolvedPost("", "", "No post link entered")
        if (raw.matches(Regex("^[0-9_]+$"))) return ResolvedPost(raw, raw, "Graph post ID supplied")

        parseDirectPostId(raw)?.let { return ResolvedPost(it, raw, "Direct Facebook post link recognized") }

        val isShareLink = runCatching {
            val u = URI(raw)
            val host = u.host.orEmpty().lowercase()
            host.endsWith("facebook.com") && u.path.orEmpty().startsWith("/share")
        }.getOrDefault(false)

        var candidateUrl = raw
        var redirectNote = ""
        if (isShareLink) {
            val redirect = resolveFacebookRedirect(raw)
            candidateUrl = redirect.first
            redirectNote = redirect.second
            parseDirectPostId(candidateUrl)?.let {
                return ResolvedPost(it, candidateUrl, "Share link resolved to Facebook post")
            }
        }

        // Graph URL lookup can resolve some public/Page URLs. Modern personal-profile posts may be denied.
        val urlsToTry = linkedSetOf(candidateUrl, raw).filter { it.isNotBlank() }
        var lastError = ""
        for (candidate in urlsToTry) {
            val graphResult = runCatching {
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                getJson("?id=$encoded", token).optString("id")
            }
            if (graphResult.isSuccess && graphResult.getOrNull().orEmpty().isNotBlank()) {
                return ResolvedPost(graphResult.getOrThrow(), candidate,
                    if (isShareLink) "Share link resolved through Meta" else "Post resolved through Meta")
            }
            lastError = cleanError(graphResult.exceptionOrNull()?.message)
        }

        val status = when {
            isShareLink && redirectNote.startsWith("Resolved") ->
                "Share link resolved, but Meta did not expose a post ID${errorSuffix(lastError)}"
            isShareLink ->
                "Could not resolve Facebook share link${if (redirectNote.isNotBlank()) ": $redirectNote" else ""}${errorSuffix(lastError)}"
            else -> "Could not resolve Facebook post${errorSuffix(lastError)}"
        }
        return ResolvedPost("", candidateUrl, status)
    }

    private fun parseDirectPostId(raw: String): String? {
        val story = Regex("[?&]story_fbid=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        val owner = Regex("[?&]id=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!story.isNullOrBlank() && !owner.isNullOrBlank()) return "${owner}_${story}"

        val post = Regex("/(?:posts|videos)/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!post.isNullOrBlank() && settings.pageId.isNotBlank()) return "${settings.pageId}_$post"

        // Some canonical links contain /permalink/<numeric id>.
        val permalink = Regex("/permalink/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!permalink.isNullOrBlank() && settings.pageId.isNotBlank()) return "${settings.pageId}_$permalink"
        return null
    }

    private fun resolveFacebookRedirect(startUrl: String): Pair<String, String> {
        var current = startUrl
        return runCatching {
            repeat(8) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val next = URL(URL(current), location).toString()
                    conn.disconnect()
                    current = next
                } else {
                    // Capture the effective URL. We intentionally do not scrape private/login HTML.
                    val effective = conn.url.toString()
                    conn.disconnect()
                    current = effective
                    return@runCatching current to if (current != startUrl) "Resolved redirect" else "Facebook returned no usable redirect"
                }
            }
            current to if (current != startUrl) "Resolved redirect chain" else "Redirect limit reached"
        }.getOrElse { startUrl to "Redirect lookup failed: ${cleanError(it.message)}" }
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
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        conn.disconnect()
        val obj = JSONObject(text.ifBlank { "{}" })
        if (code !in 200..299) {
            val msg = obj.optJSONObject("error")?.optString("message") ?: "HTTP $code"
            throw IllegalStateException(msg)
        }
        return obj
    }

    private fun errorSuffix(value: String): String = if (value.isBlank() || value == "Unknown error") "" else " ($value)"
    private fun cleanError(value: String?) = value.orEmpty().replace('\n', ' ').take(120).ifBlank { "Unknown error" }
}
