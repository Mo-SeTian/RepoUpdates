package dev.repoupdates.app

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal data class RepoName(val owner: String, val name: String) {
    val fullName get() = "$owner/$name"
    val key get() = fullName.lowercase(Locale.ROOT)
    val webUrl get() = "https://github.com/$fullName"

    companion object {
        private val ownerPattern = Regex("[A-Za-z0-9-]{1,39}")
        private val namePattern = Regex("[A-Za-z0-9_.-]{1,100}")

        fun parse(input: String): RepoName? {
            val value = input.trim()
            if (value.isEmpty() || value.any { it.isWhitespace() }) return null
            val candidate = when {
                value.startsWith("github.com/", ignoreCase = true) -> "https://$value"
                "://" in value -> value
                else -> "https://github.com/$value"
            }
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") ||
                !uri.host.equals("github.com", ignoreCase = true) ||
                uri.userInfo != null || uri.port != -1
            ) return null
            val parts = uri.rawPath.removePrefix("/").trimEnd('/').split('/')
            if (parts.size < 2 || (parts.size > 2 && parts[2] != "releases")) return null
            val owner = parts[0]
            val name = parts[1].removeSuffix(".git")
            if (!ownerPattern.matches(owner) || !namePattern.matches(name) || name in setOf(".", "..")) return null
            return RepoName(owner, name)
        }
    }
}

internal data class ProxyAddress(val host: String, val port: Int) {
    val value: String get() = "http://$host:$port"

    fun connection() = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host.removeSurrounding("[", "]"), port))

    companion object {
        fun parse(input: String): ProxyAddress? {
            val value = input.trim()
            val uri = runCatching { URI(if ("://" in value) value else "http://$value") }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.host == null ||
                uri.port !in 1..65535 || uri.userInfo != null ||
                uri.rawPath !in setOf("", "/") || uri.rawQuery != null || uri.rawFragment != null
            ) return null
            return ProxyAddress(uri.host, uri.port)
        }
    }
}

internal data class GitHubRelease(
    val id: Long,
    val tag: String,
    val notes: String,
    val publishedAt: Long,
    val prerelease: Boolean,
    val webUrl: String,
) {
    val summary get() = notes.lineSequence()
        .map { it.trim().trimStart('-', '*', ' ', '>') }
        .firstOrNull { it.isNotBlank() && !it.startsWith('#') && !it.startsWith("![") }
        ?.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        ?.replace(Regex("[*`_]"), "")
        ?.take(200)
        .orEmpty()

    companion object {
        fun fromJson(obj: JSONObject, repo: RepoName): GitHubRelease? {
            if (obj.optBoolean("draft")) return null
            val id = obj.optLong("id")
            val tag = obj.optString("tag_name")
            val publishedAt = runCatching { Instant.parse(obj.getString("published_at")).toEpochMilli() }.getOrNull()
            if (id <= 0 || tag.isBlank() || publishedAt == null) return null
            val url = repo.webUrl.toHttpUrl().newBuilder().addPathSegments("releases/tag").addPathSegment(tag).build()
            return GitHubRelease(
                id, tag, if (obj.isNull("body")) "" else obj.optString("body"),
                publishedAt, obj.optBoolean("prerelease"), url.toString(),
            )
        }
    }
}

internal data class GitHubRepository(val id: Long, val repo: RepoName)

internal data class ReleasePage(val releases: List<GitHubRelease>, val etag: String?)

internal class GitHubFailure(
    val explanation: String,
    val stopBatch: Boolean = false,
) : IOException(explanation)

internal class GitHubClient(proxy: ProxyAddress?, private val token: String?) {
    private val client = baseClient.newBuilder()
        .proxy(proxy?.connection() ?: Proxy.NO_PROXY)
        .build()

    fun publicRepository(repo: RepoName): GitHubRepository = get(repoUrl(repo)) { response ->
        val obj = JSONObject(requireNotNull(response.body).string())
        if (obj.optBoolean("private") || obj.optString("visibility") in setOf("internal", "private")) {
            throw GitHubFailure("目前支持公开的 GitHub 仓库")
        }
        val id = obj.getLong("id")
        val canonical = RepoName.parse(obj.getString("full_name"))
        if (id <= 0 || canonical == null) throw GitHubFailure("无法识别这个 GitHub 仓库")
        GitHubRepository(id, canonical)
    }

    // A rolling window keeps each repository to one request per check, including when there are no releases.
    fun releases(repo: RepoName, etag: String? = null): ReleasePage? {
        val url = repoUrl(repo).newBuilder().addPathSegment("releases").addQueryParameter("per_page", "100").build()
        return get(url, etag) { response ->
            if (response.code == 304) return@get null
            val array = JSONArray(requireNotNull(response.body).string())
            ReleasePage(
                List(array.length()) { array.getJSONObject(it) }.mapNotNull { GitHubRelease.fromJson(it, repo) },
                response.header("ETag"),
            )
        }
    }

    private fun <T> get(initialUrl: HttpUrl, etag: String? = null, read: (Response) -> T): T {
        var url = initialUrl
        repeat(4) {
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RepoUpdates-Android")
            if (token != null) request.header("Authorization", "Bearer $token")
            if (etag != null) request.header("If-None-Match", etag)
            client.newCall(request.build()).execute().use { response ->
                if (response.isSuccessful || response.code == 304) return read(response)
                if (response.code in listOf(301, 302, 307, 308)) {
                    val next = response.header("Location")?.let(url::resolve)
                    if (next?.scheme == "https" && next.host == "api.github.com" && next.port == 443 &&
                        next.username.isEmpty() && next.password.isEmpty()
                    ) {
                        url = next
                        return@repeat
                    }
                    throw GitHubFailure("GitHub 返回了无法处理的跳转")
                }
                val rateLimited = response.code == 429 || response.code == 403 &&
                    (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null ||
                        response.peekBody(4096).string().contains("rate limit", ignoreCase = true))
                throw when {
                    rateLimited -> GitHubFailure("已达到 GitHub API 请求限额，稍后重试或配置 Token", stopBatch = true)
                    response.code == 401 -> GitHubFailure("GitHub Token 无效或已过期，请在设置中更新或清除", stopBatch = true)
                    response.code == 404 -> GitHubFailure("找不到公开仓库，请检查仓库链接")
                    response.code == 403 -> GitHubFailure("GitHub 拒绝访问，请检查 Token 权限")
                    response.code == 407 -> GitHubFailure("HTTP 代理要求身份验证，请检查代理设置", stopBatch = true)
                    else -> GitHubFailure("GitHub 请求失败（HTTP ${response.code}）")
                }
            }
        }
        throw GitHubFailure("GitHub 跳转次数过多")
    }

    private fun repoUrl(repo: RepoName) = api.newBuilder()
        .addPathSegment("repos").addPathSegment(repo.owner).addPathSegment(repo.name).build()

    private companion object {
        val api = "https://api.github.com/".toHttpUrl()
        val baseClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
