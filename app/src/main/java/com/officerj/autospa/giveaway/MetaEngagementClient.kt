package com.officerj.autospa.giveaway

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val commentSummaryCount: Int? = null,
        val diagnosticLog: String = ""
    )

    private data class ResolvedPost(
        val id: String,
        val url: String,
        val status: String,
        val token: String = "",
        val source: String = "Unknown"
    )
    private data class PagePost(val id: String, val permalink: String, val createdTime: String)
    private data class UserPost(val id: String, val permalink: String, val createdTime: String, val message: String)
    private data class HttpResult(val code: Int, val contentType: String, val body: String, val url: String) {
        fun jsonOrNull(): JSONObject? = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrNull()
        val ok: Boolean get() = code in 200..299
    }

    private val log = StringBuilder()
    private var resolvedPageId: String = ""
    private var pageToken: String = ""
    private var userId: String = ""
    private val grantedPermissions = linkedSetOf<String>()

    fun scan(postUrl: String): ScanResult {
        log.clear()
        val configuredUserToken = settings.userAccessToken.trim()
        val configuredPageToken = settings.pageAccessToken.trim()
        val configuredPageId = settings.pageId.trim()
        resolvedPageId = ""
        pageToken = ""
        userId = ""
        grantedPermissions.clear()
        logLine("Officer J Meta compatibility sweep")
        logLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        logLine("Graph API: ${settings.metaApiVersion}")
        logLine("Configured Page ID: $configuredPageId")
        logLine("Configured USER access token: $configuredUserToken")
        logLine("Configured PAGE access token: $configuredPageToken")
        logLine("Post input: ${postUrl.trim()}")
        logLine("")

        if (configuredUserToken.isBlank() && configuredPageToken.isBlank()) {
            logStep("Stored credentials", false, "No User or Page access token configured")
            return ScanResult("", postUrl.trim(), "Waiting for Meta credentials", emptyMap(), emptyMap(),
                "Access token missing", "Access token missing", "Unavailable", diagnosticLog = log.toString())
        }

        // Run User-context and Page-context probes independently. Never substitute one token type for the other.
        if (configuredUserToken.isNotBlank()) {
            probeUserTokenAndDiscoverPage(configuredUserToken, configuredPageId)
            probeUserPermissions(configuredUserToken)
        } else {
            logStep("User token", false, "No User access token configured")
        }
        if (configuredPageToken.isNotBlank()) {
            probeExplicitPageToken(configuredPageToken, configuredPageId)
        } else {
            logStep("Page token", false, "No Page access token configured")
        }
        // Use whichever Page credential actually validated. The explicit token replaces the discovered one only when its /me probe identifies the configured Page.
        val workingPageToken = pageToken.ifBlank { configuredPageToken }
        val workingPageId = resolvedPageId.ifBlank { configuredPageId }

        val reactions = linkedMapOf<String, String>()
        val comments = linkedMapOf<String, String>()

        // Try both Page-owned and personal-profile routes. The returned token follows the resolved post owner.
        val resolved = resolvePost(postUrl, workingPageToken, workingPageId, configuredUserToken)
        var objectType = "Unresolved"
        var objectStatus = "No valid Graph post node was identified"
        var reactionSummary: Int? = null
        var commentSummary: Int? = null
        var reactionStatus = "Not scanned: no valid Graph post ID"
        var commentStatus = "Not scanned: no valid Graph post ID"

        if (resolved.id.isNotBlank()) {
            val engagementToken = resolved.token.ifBlank { workingPageToken }
            val inspection = inspectObject(resolved.id, engagementToken)
            objectType = inspection.first
            objectStatus = inspection.second

            reactionSummary = probeSummary(resolved.id, "reactions", engagementToken)
            commentSummary = probeSummary(resolved.id, "comments", engagementToken)

            reactionStatus = probePagedIdentities(resolved.id, "reactions", engagementToken, reactions, true, reactionSummary)
            commentStatus = probePagedIdentities(resolved.id, "comments", engagementToken, comments, false, commentSummary)
            probeEngagementFieldVariants(resolved.id, engagementToken)
        } else {
            logStep("Post engagement scan", false, "Skipped only because no real PAGEID_POSTID was identified. Other compatibility probes still completed.")
        }

        val followerStatus = probeFollowerCapability(workingPageId, workingPageToken)
        logLine("")
        logLine("=== FINAL CAPABILITY SUMMARY ===")
        logLine("Resolved Page ID: ${workingPageId.ifBlank { "Unavailable" }}")
        logLine("Page token available: ${pageToken.isNotBlank()}")
        logLine("User ID: ${userId.ifBlank { "Unavailable" }}")
        logLine("user_posts permission: ${if ("user_posts" in grantedPermissions) "granted" else "not granted"}")
        logLine("Resolved post source: ${resolved.source}")
        logLine("Resolved Post ID: ${resolved.id.ifBlank { "Unavailable" }}")
        logLine("Reaction summary: ${reactionSummary ?: "Unavailable"}")
        logLine("Reaction identities: ${reactions.size}")
        logLine("Comment summary: ${commentSummary ?: "Unavailable"}")
        logLine("Comment identities: ${comments.size}")
        logLine("Follower capability: $followerStatus")

        return ScanResult(
            resolved.id,
            resolved.url,
            resolved.status,
            reactions,
            comments,
            reactionStatus,
            commentStatus,
            followerStatus,
            objectType,
            objectStatus,
            reactionSummary,
            commentSummary,
            log.toString()
        )
    }

    private fun probeUserTokenAndDiscoverPage(userToken: String, configuredPageId: String) {
        val me = request(graphUrl("me?fields=id,name"), userToken)
        val meJson = me.jsonOrNull()
        val meId = meJson?.optString("id").orEmpty()
        val meName = meJson?.optString("name").orEmpty()
        logHttp("USER token /me", me)
        if (me.ok && meId.isNotBlank()) {
            userId = meId
            val isPageIdentity = meId == configuredPageId || meName.trim().equals("Officer J's Auto Spa", ignoreCase = true)
            logStep("User token identity", !isPageIdentity, if (isPageIdentity) "This token identifies as the Page, not the personal user" else "$meName ($meId)")
        } else logStep("User token identity", false, errorMessage(me))

        val accounts = request(graphUrl("me/accounts?fields=id,name,access_token&limit=100"), userToken)
        logHttp("USER token /me/accounts", accounts)
        if (accounts.ok) {
            val data = accounts.jsonOrNull()?.optJSONArray("data")
            var matched = false
            if (data != null) {
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val name = o.optString("name")
                    val accountToken = o.optString("access_token")
                    if (id == configuredPageId || name.trim().equals("Officer J's Auto Spa", ignoreCase = true)) {
                        matched = true
                        resolvedPageId = id.ifBlank { configuredPageId }
                        if (accountToken.isNotBlank() && pageToken.isBlank()) pageToken = accountToken
                        logStep("Officer J Page via USER token", true, "$name ($id), discovered Page token=${accountToken.isNotBlank()}")
                        break
                    }
                }
            }
            if (!matched) logStep("Officer J Page via USER token", false, "No matching Page in /me/accounts")
        } else logStep("Officer J Page via USER token", false, errorMessage(accounts))
    }

    private fun probeExplicitPageToken(explicitPageToken: String, configuredPageId: String) {
        val me = request(graphUrl("me?fields=id,name"), explicitPageToken)
        val root = me.jsonOrNull()
        val id = root?.optString("id").orEmpty()
        val name = root?.optString("name").orEmpty()
        logHttp("PAGE token /me", me)
        if (me.ok && id.isNotBlank()) {
            val matches = id == configuredPageId || name.trim().equals("Officer J's Auto Spa", ignoreCase = true)
            if (matches) {
                pageToken = explicitPageToken
                resolvedPageId = id.ifBlank { configuredPageId }
            }
            logStep("Page token identity", matches, if (matches) "$name ($id)" else "Token identifies as $name ($id), not configured Page $configuredPageId")
        } else logStep("Page token identity", false, errorMessage(me))

        if (configuredPageId.isNotBlank()) {
            val configuredProbe = request(graphUrl("$configuredPageId?fields=id,name"), explicitPageToken)
            logHttp("PAGE token configured Page probe", configuredProbe)
            logStep("Configured Page ID with Page token", configuredProbe.ok, if (configuredProbe.ok) configuredProbe.body else errorMessage(configuredProbe))
        }
    }

    private fun probeUserPermissions(token: String) {
        grantedPermissions.clear()
        val result = request(graphUrl("me/permissions"), token)
        logHttp("User permissions", result)
        if (!result.ok) {
            logStep("User permission inventory", false, errorMessage(result))
            return
        }
        val data = result.jsonOrNull()?.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                if (o.optString("status").equals("granted", ignoreCase = true)) {
                    o.optString("permission").takeIf { it.isNotBlank() }?.let { grantedPermissions += it }
                }
            }
        }
        logStep("User permission inventory", true, grantedPermissions.sorted().joinToString(", ").ifBlank { "No granted permissions returned" })
        logStep(
            "user_posts permission",
            "user_posts" in grantedPermissions,
            if ("user_posts" in grantedPermissions) "Granted; personal Timeline enumeration can be tested" else "Not granted. Personal-post endpoints will still be probed so the API response is captured."
        )
    }

    private fun resolvePost(postUrl: String, token: String, pageId: String, userToken: String): ResolvedPost {
        val raw = postUrl.trim()
        if (raw.isBlank()) return ResolvedPost("", "", "No post link entered")
        if (isGraphPostId(raw)) {
            logStep("Direct Graph Post ID", true, raw)
            val directToken = if (pageId.isNotBlank() && raw.startsWith("${pageId}_")) token else userToken
            return ResolvedPost(raw, raw, "Graph post ID supplied", directToken, if (directToken == token) "Officer J Page" else "Direct/unknown")
        }

        parseDirectPostId(raw, pageId)?.let {
            logStep("Direct Facebook URL parser", true, it)
            val directToken = if (pageId.isNotBlank() && it.startsWith("${pageId}_")) token else userToken
            return ResolvedPost(it, raw, "Direct Facebook post link recognized", directToken, if (directToken == token) "Officer J Page" else "Direct URL")
        }

        val isShareLink = runCatching {
            val u = URI(raw)
            val host = u.host.orEmpty().lowercase()
            host.endsWith("facebook.com") && u.path.orEmpty().startsWith("/share")
        }.getOrDefault(false)

        var candidateUrl = raw
        if (isShareLink) {
            val redirect = resolveFacebookRedirect(raw)
            candidateUrl = redirect.first
            logStep("Share-link HTTP redirect", redirect.second.startsWith("Resolved"), "${redirect.second}; final=$candidateUrl")
            parseDirectPostId(candidateUrl, pageId)?.let {
                val directToken = if (pageId.isNotBlank() && it.startsWith("${pageId}_")) token else userToken
                return ResolvedPost(it, candidateUrl, "Share link redirected to a canonical Facebook post", directToken, if (directToken == token) "Officer J Page" else "Redirect URL")
            }
        } else logStep("Share-link HTTP redirect", true, "Not required for this URL")

        // URL/Open Graph lookup test. Never accept a URL-shaped ID as a Facebook Post ID.
        var graphLookupError = ""
        for (candidate in linkedSetOf(candidateUrl, raw).filter { it.isNotBlank() }) {
            val encoded = URLEncoder.encode(candidate, "UTF-8")
            val result = request(graphUrl("?id=$encoded"), token, tokenAlreadyInUrl = false)
            logHttp("URL Graph lookup", result)
            val graphId = result.jsonOrNull()?.optString("id").orEmpty()
            if (isGraphPostId(graphId)) {
                logStep("URL -> Graph Post ID", true, graphId)
                val directToken = if (pageId.isNotBlank() && graphId.startsWith("${pageId}_")) token else userToken
                return ResolvedPost(graphId, candidate, "Post resolved to a real Graph post ID", directToken, if (directToken == token) "Officer J Page" else "URL Graph lookup")
            }
            graphLookupError = if (graphId.isNotBlank()) "Meta returned non-post id: $graphId" else errorMessage(result)
            logStep("URL -> Graph Post ID", false, graphLookupError)
        }

        // Page-owned post enumeration is attempted even if URL resolution failed.
        if (pageId.isNotBlank()) {
            val discovery = discoverPagePost(raw, candidateUrl, pageId, token)
            if (discovery != null) {
                logStep("Page post match", true, "${discovery.id} ${discovery.permalink}")
                return ResolvedPost(discovery.id, discovery.permalink.ifBlank { candidateUrl }, "Matched against posts owned by Officer J's Page", token, "Officer J Page")
            }
            logStep("Page post match", false, "No matching Page-owned post found. If the original giveaway was posted on a personal profile, the Pages API cannot turn it into a Page-owned post.")
        } else logStep("Page post enumeration", false, "No usable Page ID")

        // Personal-profile route: use the original user token, never the Page token.
        val userDiscovery = if (userToken.isNotBlank()) discoverUserPost(raw, candidateUrl, userToken) else null
        if (userToken.isBlank()) logStep("Personal profile enumeration", false, "No User token configured")
        if (userDiscovery != null) {
            logStep("Personal profile post match", true, "${userDiscovery.id} ${userDiscovery.permalink}")
            return ResolvedPost(
                userDiscovery.id,
                userDiscovery.permalink.ifBlank { candidateUrl },
                "Matched against posts on the authenticated user's Timeline",
                userToken,
                "Personal profile"
            )
        }
        logStep("Personal profile post match", false, "No usable personal-profile post could be matched. See /me/posts and /me/feed probes in this log.")

        val message = if (isShareLink) {
            "Could not map Facebook share link to a valid Page post ID. Full compatibility log is available below."
        } else {
            "Could not resolve Facebook post to a valid Page post ID. Full compatibility log is available below."
        }
        if (graphLookupError.isNotBlank()) logLine("Last Graph lookup detail: $graphLookupError")
        return ResolvedPost("", candidateUrl, message)
    }

    private fun discoverPagePost(rawUrl: String, candidateUrl: String, pageId: String, token: String): PagePost? {
        val targets = linkedSetOf(normalizeFacebookUrl(rawUrl), normalizeFacebookUrl(candidateUrl)).filter { it.isNotBlank() }.toSet()
        val numericHints = extractNumericHints(rawUrl) + extractNumericHints(candidateUrl)
        var next: String? = graphUrl("$pageId/published_posts?fields=id,permalink_url,created_time,message&limit=100")
        var pages = 0
        var total = 0
        while (!next.isNullOrBlank() && pages++ < 5) {
            val result = request(next, token)
            logHttp("published_posts page $pages", result)
            if (!result.ok) {
                logStep("Page published_posts", false, errorMessage(result))
                return null
            }
            val root = result.jsonOrNull()
            if (root == null) {
                logStep("Page published_posts JSON", false, "Response was not valid JSON: ${result.body.take(500)}")
                return null
            }
            val data = root.optJSONArray("data")
            if (data != null) {
                total += data.length()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val permalink = o.optString("permalink_url")
                    val created = o.optString("created_time")
                    if (!isGraphPostId(id)) continue
                    val normalizedPermalink = normalizeFacebookUrl(permalink)
                    val idTail = id.substringAfterLast('_')
                    if ((normalizedPermalink.isNotBlank() && normalizedPermalink in targets) ||
                        (idTail.isNotBlank() && idTail in numericHints) ||
                        numericHints.any { hint -> hint.isNotBlank() && normalizedPermalink.contains(hint) }) {
                        logStep("Page published_posts", true, "Scanned $total posts; permalink/ID match found")
                        return PagePost(id, permalink, created)
                    }
                }
            }
            next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
        }
        logStep("Page published_posts", true, "Endpoint compatible; scanned $total recent Page posts across ${pages.coerceAtMost(5)} page(s), no supplied-link match")
        return null
    }

    private fun discoverUserPost(rawUrl: String, candidateUrl: String, token: String): UserPost? {
        if (token.isBlank()) {
            logStep("Personal profile enumeration", false, "No user token")
            return null
        }
        val targets = linkedSetOf(normalizeFacebookUrl(rawUrl), normalizeFacebookUrl(candidateUrl)).filter { it.isNotBlank() }.toSet()
        val numericHints = extractNumericHints(rawUrl) + extractNumericHints(candidateUrl)
        val candidates = mutableListOf<Pair<UserPost, Int>>()
        var directMatch: UserPost? = null

        fun enumerate(edge: String) {
            if (directMatch != null) return
            var next: String? = graphUrl("me/$edge?fields=id,permalink_url,created_time,message&limit=100")
            var pages = 0
            var total = 0
            while (!next.isNullOrBlank() && pages++ < 5) {
                val result = request(next, token)
                logHttp("personal $edge page $pages", result)
                if (!result.ok) {
                    logStep("Personal $edge endpoint", false, errorMessage(result))
                    return
                }
                val root = result.jsonOrNull()
                if (root == null) {
                    logStep("Personal $edge JSON", false, "Response was not valid JSON")
                    return
                }
                val data = root.optJSONArray("data")
                if (data != null) {
                    total += data.length()
                    for (i in 0 until data.length()) {
                        val o = data.optJSONObject(i) ?: continue
                        val id = o.optString("id")
                        val permalink = o.optString("permalink_url")
                        val created = o.optString("created_time")
                        val message = o.optString("message")
                        if (!isGraphPostId(id)) continue
                        val post = UserPost(id, permalink, created, message)
                        val normalizedPermalink = normalizeFacebookUrl(permalink)
                        val idTail = id.substringAfterLast('_')
                        if ((normalizedPermalink.isNotBlank() && normalizedPermalink in targets) ||
                            (idTail.isNotBlank() && idTail in numericHints) ||
                            numericHints.any { hint -> hint.isNotBlank() && normalizedPermalink.contains(hint) }) {
                            directMatch = post
                            logStep("Personal $edge endpoint", true, "Scanned $total posts; permalink/ID match found")
                            return
                        }
                        val score = giveawayCandidateScore(message, created)
                        if (score > 0) candidates += post to score
                    }
                }
                next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
            }
            logStep("Personal $edge endpoint", true, "Endpoint compatible; scanned $total posts across ${pages.coerceAtMost(5)} page(s), no supplied-link match")
        }

        enumerate("posts")
        if (directMatch == null) enumerate("feed")
        directMatch?.let { return it }

        // Share URLs contain no post number. When URL matching is impossible, rank giveaway-looking posts.
        val ranked = candidates
            .distinctBy { it.first.id }
            .sortedWith(compareByDescending<Pair<UserPost, Int>> { it.second }.thenByDescending { it.first.createdTime })
        if (ranked.isNotEmpty()) {
            logLine("Personal-profile giveaway candidates:")
            ranked.take(10).forEachIndexed { index, (post, score) ->
                val snippet = post.message.replace(Regex("\\s+"), " ").take(180)
                logLine("  ${index + 1}. score=$score id=${post.id} created=${post.createdTime} permalink=${post.permalink} message=$snippet")
            }
        } else {
            logLine("Personal-profile giveaway candidates: none returned")
        }

        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        // High threshold + separation avoids silently selecting an unrelated personal post.
        if (best != null && best.second >= 8 && (second == null || best.second >= second.second + 3)) {
            logStep("Personal giveaway heuristic", true, "Selected ${best.first.id} with score=${best.second}; next score=${second?.second ?: 0}")
            return best.first
        }
        logStep(
            "Personal giveaway heuristic",
            false,
            if (best == null) "No candidate post" else "Best score=${best.second}; not unique/confident enough to auto-select"
        )
        return null
    }

    private fun giveawayCandidateScore(message: String, createdTime: String): Int {
        if (message.isBlank()) return 0
        val text = message.lowercase(Locale.US)
        var score = 0
        fun has(term: String, points: Int) { if (text.contains(term)) score += points }
        has("giveaway", 5)
        has("winner", 3)
        has("maintenance", 3)
        has("one year", 3)
        has("1 year", 3)
        has("follow officer j", 3)
        has("officer j's auto spa", 1)
        has("like", 1)
        has("comment", 1)
        has("share", 1)
        has("ceramic", 1)
        // Current promotions are usually among the newest Timeline posts; this is only a tie-breaker signal.
        if (createdTime.startsWith("2026-08")) score += 1
        return score
    }

    private fun probeEngagementFieldVariants(id: String, token: String) {
        val tests = listOf(
            "reactions default" to "$id/reactions?limit=5",
            "reactions id-type" to "$id/reactions?fields=id,type&limit=5",
            "reactions id-name-type" to "$id/reactions?fields=id,name,type&limit=5",
            "comments default" to "$id/comments?limit=5",
            "comments id-message-time" to "$id/comments?fields=id,message,created_time&limit=5",
            "comments with-from" to "$id/comments?fields=id,message,from,created_time&limit=5"
        )
        for ((label, path) in tests) {
            val result = request(graphUrl(path), token)
            logHttp("Field compatibility: $label", result)
            logStep("Field compatibility: $label", result.ok, if (result.ok) "Supported" else errorMessage(result))
        }
    }

    private fun inspectObject(id: String, token: String): Pair<String, String> {
        val result = request(graphUrl("$id?metadata=1&fields=id,permalink_url,created_time"), token)
        logHttp("Post object inspection", result)
        if (!result.ok) {
            logStep("Post object inspection", false, errorMessage(result))
            return "Post" to "Post identified; inspection unavailable: ${errorMessage(result)}"
        }
        val root = result.jsonOrNull() ?: return "Post" to "Post response was not valid JSON"
        val type = root.optJSONObject("metadata")?.optString("type").orEmpty().ifBlank { "Post" }
        logStep("Post object inspection", true, "type=$type, id=${root.optString("id")}")
        return type to "Graph post reachable"
    }

    private fun probeSummary(id: String, edge: String, token: String): Int? {
        val result = request(graphUrl("$id/$edge?limit=0&summary=true"), token)
        logHttp("$edge summary", result)
        if (!result.ok) {
            logStep("$edge summary", false, errorMessage(result))
            return null
        }
        val count = result.jsonOrNull()?.optJSONObject("summary")?.let { if (it.has("total_count")) it.optInt("total_count") else null }
        logStep("$edge summary", count != null, if (count != null) "total_count=$count" else "Endpoint returned no total_count")
        return count
    }

    private fun probePagedIdentities(
        id: String,
        edge: String,
        token: String,
        out: MutableMap<String, String>,
        reactions: Boolean,
        summary: Int?
    ): String {
        var next: String? = graphUrl("$id/$edge?limit=100")
        var total = 0
        var pages = 0
        var lastError = ""
        while (!next.isNullOrBlank() && pages++ < 100) {
            val result = request(next, token)
            logHttp("$edge identities page $pages", result)
            if (!result.ok) {
                lastError = errorMessage(result)
                logStep("$edge identities", false, lastError)
                break
            }
            val root = result.jsonOrNull()
            if (root == null) {
                lastError = "Response was not JSON"
                logStep("$edge identities", false, lastError)
                break
            }
            val data = root.optJSONArray("data")
            if (data != null) {
                total += data.length()
                for (i in 0 until data.length()) {
                    val o = data.optJSONObject(i) ?: continue
                    if (reactions) {
                        // Different Graph versions may return id/name directly, or only an id/type.
                        val pid = o.optString("id")
                        val name = o.optString("name")
                        if (pid.isNotBlank() && name.isNotBlank()) out[pid] = name
                    } else {
                        val from = o.optJSONObject("from")
                        val pid = from?.optString("id").orEmpty()
                        val name = from?.optString("name").orEmpty()
                        if (pid.isNotBlank() && name.isNotBlank()) out[pid] = name
                    }
                }
            }
            next = root.optJSONObject("paging")?.optString("next")?.takeIf { it.isNotBlank() }
        }
        if (lastError.isNotBlank()) return "Unavailable: $lastError"
        logStep("$edge identities", true, "rows=$total, named identities=${out.size}, summary=${summary ?: "Unavailable"}")
        return when {
            total == 0 && summary != null && summary > 0 -> "Edge returned 0 identities; Meta summary reports $summary"
            total == 0 && summary != null -> "Loaded 0 (summary $summary)"
            total == 0 -> "Loaded 0 (no summary available)"
            out.size == total -> "Loaded $total"
            out.isNotEmpty() -> "Counted $total; ${out.size} identities available"
            else -> "Counted $total; Meta withheld identities"
        }
    }

    private fun probeFollowerCapability(pageId: String, token: String): String {
        if (pageId.isBlank()) {
            logStep("Page follower count", false, "No Page ID")
            return "Unavailable: no Page ID"
        }
        val result = request(graphUrl("$pageId?fields=id,name,followers_count,fan_count"), token)
        logHttp("Page follower count", result)
        if (!result.ok) {
            logStep("Page follower count", false, errorMessage(result))
            return "Individual follower lookup unavailable; count probe failed: ${errorMessage(result)}"
        }
        val root = result.jsonOrNull()
        val followerCount = if (root?.has("followers_count") == true) root.optLong("followers_count") else null
        val fanCount = if (root?.has("fan_count") == true) root.optLong("fan_count") else null
        logStep("Page follower count", true, "followers_count=${followerCount ?: "not exposed"}, fan_count=${fanCount ?: "not exposed"}")
        logStep("Individual follower identities", false, "No reliable supported Page API edge for a complete person-by-person follower list; keep manual verification fallback")
        return buildString {
            append("Individual follower identities unavailable")
            if (followerCount != null) append(" • Page followers: $followerCount")
        }
    }

    private fun parseDirectPostId(raw: String, pageId: String): String? {
        val story = Regex("[?&]story_fbid=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        val owner = Regex("[?&]id=([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!story.isNullOrBlank() && !owner.isNullOrBlank()) return "${owner}_${story}"
        val ownerPost = Regex("/(\\d+)/(?:posts|videos)/(\\d+)").find(raw)
        if (ownerPost != null) return "${ownerPost.groupValues[1]}_${ownerPost.groupValues[2]}"
        val post = Regex("/(?:posts|videos)/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!post.isNullOrBlank() && pageId.isNotBlank()) return "${pageId}_$post"
        val permalink = Regex("/permalink/([0-9]+)").find(raw)?.groupValues?.getOrNull(1)
        if (!permalink.isNullOrBlank() && pageId.isNotBlank()) return "${pageId}_$permalink"
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
            if (!story.isNullOrBlank() && !owner.isNullOrBlank()) "facebook.com/$owner/posts/$story" else "facebook.com$path"
        }.getOrElse { value.trim().trimEnd('/') }
    }

    private fun extractNumericHints(value: String): Set<String> =
        Regex("(?<![A-Za-z0-9])[0-9]{6,}(?![A-Za-z0-9])").findAll(value).map { it.value }.toSet()

    private fun isGraphPostId(value: String): Boolean = value.matches(Regex("^[0-9]+_[0-9]+$"))

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
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val next = URL(URL(current), location).toString()
                    conn.disconnect(); current = next
                } else {
                    val effective = conn.url.toString(); conn.disconnect(); current = effective
                    return@runCatching current to if (current != startUrl) "Resolved redirect" else "Facebook returned no usable redirect"
                }
            }
            current to if (current != startUrl) "Resolved redirect chain" else "Redirect limit reached"
        }.getOrElse { startUrl to "Redirect lookup failed: ${cleanError(it.message)}" }
    }

    private fun graphUrl(path: String): String {
        val p = if (path.startsWith("/")) path.substring(1) else path
        return "https://graph.facebook.com/${settings.metaApiVersion.trim().ifBlank { "v26.0" }}/$p"
    }

    private fun request(urlString: String, token: String, tokenAlreadyInUrl: Boolean = false): HttpResult {
        val finalUrl = if (tokenAlreadyInUrl || token.isBlank()) urlString else {
            val joiner = if (urlString.contains("?")) "&" else "?"
            urlString + joiner + "access_token=" + URLEncoder.encode(token, "UTF-8")
        }
        return try {
            val conn = URL(finalUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 18000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "OfficerJGiveaway/1.1.7 Android")
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
            conn.disconnect()
            HttpResult(code, contentType, text, finalUrl)
        } catch (e: Exception) {
            HttpResult(0, "", "${e.javaClass.simpleName}: ${e.message}", finalUrl)
        }
    }

    private fun logHttp(label: String, result: HttpResult) {
        logLine("[$label] HTTP ${result.code}${if (result.contentType.isNotBlank()) " ${result.contentType}" else ""}")
        logLine("Request: ${result.url}")
        logLine("Response: ${result.body.replace(Regex("\\s+"), " ").take(4000)}")
    }

    private fun logStep(label: String, pass: Boolean, detail: String) {
        logLine("${if (pass) "PASS" else "FAIL"} — $label — $detail")
    }

    private fun logLine(value: String) { log.append(value).append('\n') }

    private fun errorMessage(result: HttpResult): String {
        val obj = result.jsonOrNull()
        return obj?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: if (result.code == 0) result.body else "HTTP ${result.code}: ${result.body.take(300)}"
    }

    private fun cleanError(value: String?): String = value.orEmpty().replace('\n', ' ').trim().take(400).ifBlank { "Unknown error" }
}
