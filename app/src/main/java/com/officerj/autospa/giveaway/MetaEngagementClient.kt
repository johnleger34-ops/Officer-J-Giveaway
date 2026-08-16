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
        val followerStatus: String,
        val objectType: String = "Unknown",
        val objectStatus: String = "Not inspected",
        val reactionSummaryCount: Int? = null,
        val commentSummaryCount: Int? = null
    )

    private data class ResolvedPost(val id: String, val url: String, val status: String)
    private data class PagePost(val id: String, val permalink: String, val createdTime: String)

    fun scan(postUrl: String): ScanResult {
        val token = settings.accessToken.trim()
        if (token.isBlank()) return ScanResult(
            "", postUrl.trim(), "Waiting for Meta access token",
            emptyMap(), emptyMap(), "Access token missing", "Access token missing", "Unavailable"
        )

        val resolved = resolvePost(postUrl, token)
        if (resolved.id.isBlank()) return ScanResult(
            "", resolved.url, resolved.status,
            emptyMap(), emptyMap(),
            "Not scanned: no valid Graph post ID", "Not scanned: no valid Graph post ID",
            "Individual follower lookup is not exposed reliably; manual verification remains available",
            objectType = "Unresolved",
            objectStatus = "No valid Graph post node was identified"
        )

        val reactions = linkedMapOf<String, String>()
        val comments = linkedMapOf<String, String>()

        val objectInspection = inspectObject(resolved.id, token)
        val reactionSummary = readSummaryCount(resolved.id, "reactions", token)
        val commentSummary = readSummaryCount(resolved.id, "comments", token)

        val reactionStatus = runCatching {
            val rawCount = readPaged("${resolved.id}/reactions", token, null) { o ->
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) reactions[id] = name
            }
            when {
                rawCount == 0 && reactionSummary != null && reactionSummary > 0 ->
                    "Edge returned 0 identities; Meta summary reports $reactionSummary reactions"
                rawCount == 0 && reactionSummary != null -> "Loaded 0 (summary $reactionSummary)"
                rawCount == 0 -> "Loaded 0 (no summary available)"
                reactions.size == rawCount -> "Loaded $rawCount"
                reactions.isNotEmpty() -> "Counted $rawCount; ${reactions.size} participant identities available"
                else -> "Counted $rawCount; Facebook withheld participant identities"
            }
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        val commentStatus = runCatching {
            val rawCount = readPaged("${resolved.id}/comments", token, null) { o ->
                val from = o.optJSONObject("from")
                val id = from?.optString("id").orEmpty()
                val name = from?.optString("name").orEmpty()
                if (id.isNotBlank() && name.isNotBlank()) comments[id] = name
            }
            when {
                rawCount == 0 && commentSummary != null && commentSummary > 0 ->
                    "Edge returned 0 identities; Meta summary reports $commentSummary comments"
                rawCount == 0 && commentSummary != null -> "Loaded 0 (summary $commentSummary)"
                rawCount == 0 -> "Loaded 0 (no summary available)"
                comments.size == rawCount -> "Loaded $rawCount"
                comments.isNotEmpty() -> "Counted $rawCount; ${comments.size} commenter identities available"
                else -> "Counted $rawCount; Facebook withheld commenter identities"
            }
        }.getOrElse { "Unavailable: ${cleanError(it.message)}" }

        return ScanResult(
            resolved.id,
            resolved.url,
            resolved.status,
            reactions,
            comments,
            reactionStatus,
            commentStatus,
            "Individual follower lookup is not exposed reliably; manual verification remains available",
            objectInspection.first,
            objectInspection.second,
            reactionSummary,
            commentSummary
        )
    }

    private fun resolvePost(postUrl: String, token: String): ResolvedPost {
        val raw = postUrl.trim()
        if (raw.isBlank()) return ResolvedPost("", "", "No post link entered")
        if (isGraphPostId(raw)) return ResolvedPost(raw, raw, "Graph post ID supplied")

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
                return ResolvedPost(it, candidateUrl, "Share link redirected to a canonical Facebook post")
            }
        }

        // Facebook's URL lookup can legally return the URL itself as an Open Graph node ID.
        // That is NOT a Facebook Post Graph ID and must never be used for /reactions or /comments.
        val urlsToTry = linkedSetOf(candidateUrl, raw).filter { it.isNotBlank() }
        var lastError = ""
        for (candidate in urlsToTry) {
            val graphResult = runCatching {
                val encoded = URLEncoder.encode(candidate, "UTF-8")
                getJson("?id=$encoded", token).optString("id")
            }
            val graphId = graphResult.getOrNull().orEmpty()
            if (isGraphPostId(graphId)) {
                return ResolvedPost(graphId, candidate,
                    if (isShareLink) "Share link resolved to a real Graph post ID" else "Post resolved to a real Graph post ID")
            }
            if (graphId.isNotBlank() && !isGraphPostId(graphId)) {
                lastError = "Meta returned a URL/Open Graph node instead of a Facebook Post ID"
            } else if (graphResult.isFailure) {
                lastError = cleanError(graphResult.exceptionOrNull()?.message)
            }
        }

        // Reliable Page fallback: enumerate recent posts owned by the configured Page and
        // match the canonical permalink. This works for Page-owned posts and avoids treating
        // facebook.com/share/... as a post object.
        val pageId = settings.pageId.trim()
        if (pageId.isNotBlank()) {
            val discovery = runCatching { discoverPagePost(raw, candidateUrl, pageId, token) }
            val found = discovery.getOrNull()
            if (found != null) {
                return ResolvedPost(found.id, found.permalink.ifBlank { candidateUrl },
                    "Matched against recent posts owned by Officer J's Page")
            }
            if (discovery.isFailure) {
                lastError = "Page post lookup failed: ${cleanError(discovery.exceptionOrNull()?.message)}"
            } else {
                val suffix = errorSuffix(lastError)
                return ResolvedPost(
                    "",
                    candidateUrl,
                    if (isShareLink) {
                        "Share link is not mappable to a recent Officer J Page post. If the original giveaway was posted on your personal profile, the Page API cannot scan that profile-owned post.$suffix"
                    } else {
                        "Post was not found among recent posts owned by Officer J's Page. A personal-profile post is outside this Page token's readable Page content.$suffix"
                    }
                )
            }
        }

        val status = when {
            isShareLink && redirectNote.startsWith("Resolved") ->
                "Share link redirected, but no valid Facebook Post Graph ID was exposed${errorSuffix(lastError)}"
            isShareLink ->
                "Could not map Facebook share link to a valid Post Graph ID${if (redirectNote.isNotBlank()) ": $redirectNote" else ""}${errorSuffix(lastError)}"
            else -> "Could not resolve Facebook post to a valid Graph post ID${errorSuffix(lastError)}"
        }
        return ResolvedPost("", candidateUrl, status)
    }

    private fun discoverPagePost(rawUrl: String, candidateUrl: String, pageId: String, token: String): PagePost? {
        val targets = linkedSetOf(normalizeFacebookUrl(rawUrl), normalizeFacebookUrl(candidateUrl)).filter { it.isNotBlank() }.toSet()
        val numericHints = extractNumericHints(rawUrl) + extractNumericHints(candidateUrl)

        var next: String? = graphUrl("$pageId/published_posts?fields=id,permalink_url,created_time&limit=100") +
            "&access_token=${URLEncoder.encode(token, "UTF-8")}"
        var pages = 0
        while (!next.isNullOrBlank() && pages++ < 5) {
            val root = getJsonAbsolute(next)
            val data = root.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    val id = o.optString("id")
                    val permalink = o.optString("permalink_url")
                    val created = o.optString("created_time")
                    if (!isGraphPostId(id)) continue
                    val normalizedPermalink = normalizeFacebookUrl(permalink)
                    val idTail = id.substringAfterLast('_')
                    if ((normalizedPermalink.isNotBlank() && normalizedPermalink in targets) ||
                        (idTail.isNotBlank() && idTail in numericHints) ||
                        numericHints.any { hint -> hint.isNotBlank() && normalizedPermalink.contains(hint) }) {
                        return PagePost(id, permalink, created)
                    }
                }
            }
            next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun normalizeFacebookUrl(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val uri = URI(value.trim())
            val host = uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.")
            if (!host.endsWith("facebook.com")) return@runCatching value.trim().trimEnd('/')
            val path = uri.path.orEmpty().replace(Regex("/+"), "/").trimEnd('/')
            val query = uri.rawQuery.orEmpty()
            val story = Regex("(?:^|&)story_fbid=([0-9]+)").find(query)?.groupValues?.getOrNull(1)
            val owner = Regex("(?:^|&)id=([0-9]+)").find(query)?.groupValues?.getOrNull(1)
            if (!story.isNullOrBlank() && !owner.isNullOrBlank()) {
                "facebook.com/$owner/posts/$story"
            } else {
                "facebook.com$path"
            }
        }.getOrElse { value.trim().trimEnd('/') }
    }

    private fun extractNumericHints(value: String): Set<String> =
        Regex("(?<![A-Za-z0-9])[0-9]{6,}(?![A-Za-z0-9])").findAll(value).map { it.value }.toSet()

    private fun isGraphPostId(value: String): Boolean = value.matches(Regex("^[0-9]+_[0-9]+$"))

    private fun parseDirectPostId(raw: String): String? {
        val story = Regex("[?&]story_fbid=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        val owner = Regex("[?&]id=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!story.isNullOrBlank() && !owner.isNullOrBlank()) return "${owner}_${story}"

        val post = Regex("/(?:posts|videos)/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!post.isNullOrBlank() && settings.pageId.isNotBlank()) return "${settings.pageId}_$post"

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
                    val effective = conn.url.toString()
                    conn.disconnect()
                    current = effective
                    return@runCatching current to if (current != startUrl) "Resolved redirect" else "Facebook returned no usable redirect"
                }
            }
            current to if (current != startUrl) "Resolved redirect chain" else "Redirect limit reached"
        }.getOrElse { startUrl to "Redirect lookup failed: ${cleanError(it.message)}" }
    }

    private fun inspectObject(id: String, token: String): Pair<String, String> {
        if (!isGraphPostId(id)) return "Invalid" to "Refused to inspect a non-Post Graph ID"
        return runCatching {
            val root = getJson("$id?metadata=1&fields=id,permalink_url,created_time", token)
            val type = root.optJSONObject("metadata")?.optString("type").orEmpty().ifBlank { "Post" }
            val permalink = root.optString("permalink_url")
            val created = root.optString("created_time")
            val details = buildString {
                append("Graph post reachable")
                if (created.isNotBlank()) append(" • created $created")
                if (permalink.isNotBlank()) append(" • permalink exposed")
            }
            type to details
        }.getOrElse { "Post" to "Post identified; optional inspection fields unavailable: ${cleanError(it.message)}" }
    }

    private fun readSummaryCount(id: String, edge: String, token: String): Int? {
        if (!isGraphPostId(id)) return null
        return runCatching {
            val root = getJson("$id/$edge?limit=0&summary=true", token)
            root.optJSONObject("summary")?.let { summary ->
                if (summary.has("total_count")) summary.optInt("total_count") else null
            }
        }.getOrNull()
    }

    private fun readPaged(path: String, token: String, fields: String?, consume: (JSONObject) -> Unit): Int {
        val query = buildString {
            append("$path?limit=100")
            if (!fields.isNullOrBlank()) append("&fields=${URLEncoder.encode(fields, "UTF-8")}")
        }
        var next: String? = graphUrl(query) + "&access_token=${URLEncoder.encode(token, "UTF-8")}"
        var pages = 0
        var total = 0
        while (!next.isNullOrBlank() && pages++ < 100) {
            val root = getJsonAbsolute(next)
            val error = root.optJSONObject("error")
            if (error != null) throw IllegalStateException(error.optString("message", "Meta API error"))
            val data = root.optJSONArray("data")
            if (data != null) {
                total += data.length()
                for (i in 0 until data.length()) consume(data.getJSONObject(i))
            }
            next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
        }
        return total
    }

    private fun getJson(path: String, token: String): JSONObject {
        val joiner = if (path.contains("?")) "&" else "?"
        return getJsonAbsolute(graphUrl(path) + joiner + "access_token=${URLEncoder.encode(token, "UTF-8")}")
    }

    private fun graphUrl(path: String): String {
        val p = if (path.startsWith("/")) path.substring(1) else path
        return "https://graph.facebook.com/${settings.metaApiVersion.trim().ifBlank { "v26.0" }}/$p"
    }

    private fun getJsonAbsolute(urlString: String): JSONObject {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 18000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "OfficerJGiveaway/1.1.5 Android")

        val code = conn.responseCode
        val contentType = conn.contentType.orEmpty()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        conn.disconnect()

        val obj = try {
            JSONObject(text.ifBlank { "{}" })
        } catch (e: Exception) {
            val preview = sanitizeResponsePreview(text)
            throw IllegalStateException(
                "Invalid JSON from Meta (HTTP $code${if (contentType.isNotBlank()) ", $contentType" else ""}). " +
                    "Response preview: $preview"
            )
        }

        if (code !in 200..299) {
            val msg = obj.optJSONObject("error")?.optString("message") ?: "HTTP $code"
            throw IllegalStateException(msg)
        }
        return obj
    }

    private fun sanitizeResponsePreview(value: String): String {
        if (value.isBlank()) return "<empty response>"
        return value
            .replace(Regex("""access_token=[^&"\'\s<>]+""", RegexOption.IGNORE_CASE), "access_token=<redacted>")
            .replace(Regex("EA[A-Za-z0-9_-]{20,}"), "<token-redacted>")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(280)
            .ifBlank { "<empty response>" }
    }

    private fun errorSuffix(value: String): String = if (value.isBlank() || value == "Unknown error") "" else " ($value)"
    private fun cleanError(value: String?) = value.orEmpty().replace('\n', ' ').take(420).ifBlank { "Unknown error" }
}
