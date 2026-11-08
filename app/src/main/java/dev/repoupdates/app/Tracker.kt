package dev.repoupdates.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

internal data class Preferences(
    val background: Boolean,
    val intervalHours: Int,
    val prereleases: Boolean,
    val notifications: Boolean,
    val appearance: String,
    val proxy: String,
    val hasToken: Boolean,
)

internal data class TrackerState(
    val subscriptions: List<Subscription>,
    val feed: List<FeedItem>,
    val preferences: Preferences,
    val checkedAt: Long,
    val refreshing: Boolean,
    val checkResult: CheckResult?,
)

internal data class CheckResult(val total: Int, val checked: Int, val failed: Int, val error: String?) {
    val skipped get() = total - checked - failed
}

internal data class HistoryPage(
    val releases: List<FeedItem>,
    val hasMore: Boolean,
    val error: String? = null,
)

class TrackerApplication : Application() {
    internal lateinit var tracker: Tracker
        private set

    override fun onCreate() {
        super.onCreate()
        tracker = Tracker(this)
        createUpdatesChannel(this)
        scheduleChecks(this, tracker.preferences())
    }
}

internal class Tracker(private val app: Application) {
    private val prefs = app.getSharedPreferences("repo-updates", Context.MODE_PRIVATE)
    private val store = TrackerStore(app)
    private val vault = TokenVault(prefs)
    private val refreshLock = Mutex()
    private val stateLock = Mutex()
    private val historyLock = Mutex()
    private var refreshing = false
    private val stateFlow = MutableStateFlow(TrackerState(emptyList(), emptyList(), preferences(), 0, false, null))
    val state: StateFlow<TrackerState> = stateFlow

    fun preferences() = Preferences(
        background = prefs.getBoolean("background", true),
        intervalHours = prefs.getInt("interval", 6),
        prereleases = prefs.getBoolean("prereleases", false),
        notifications = prefs.getBoolean("notifications", true),
        appearance = prefs.getString("appearance", "system") ?: "system",
        proxy = prefs.getString("proxy", "") ?: "",
        hasToken = vault.configured,
    )

    suspend fun load() = withContext(Dispatchers.IO) { publish() }

    suspend fun add(input: String): String? = withContext(Dispatchers.IO) {
        val repo = RepoName.parse(input) ?: return@withContext "请输入公开 GitHub 仓库的链接或 owner/repo"
        if (store.subscriptions().any { it.repo.key == repo.key }) return@withContext "已订阅这个仓库"
        try {
            val client = client()
            val canonical = client.publicRepository(repo)
            val baseline = requireNotNull(client.releases(canonical.repo))
            if (!store.add(canonical, baseline)) return@withContext "已订阅这个仓库"
            prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
            publish()
            null
        } catch (error: GitHubFailure) {
            error.explanation
        } catch (_: IOException) {
            connectionError()
        } catch (_: JSONException) {
            "GitHub 返回的数据无法读取，请稍后重试"
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        store.remove(id)
        publish()
    }

    suspend fun open(id: Long): ReleaseDetail? = withContext(Dispatchers.IO) {
        val detail = store.open(id)
        if (detail != null) {
            dismissUpdateNotification(app, id)
            publish()
        }
        detail
    }

    suspend fun history(subscriptionId: Long, beforeId: Long?, includePrereleases: Boolean): HistoryPage =
        withContext(Dispatchers.IO) {
            historyLock.withLock {
                val pageSize = 20
                var rows = store.history(subscriptionId, beforeId, includePrereleases, pageSize + 1)
                var subscription = store.subscription(subscriptionId)
                var error: String? = null
                try {
                    val client by lazy { client() }
                    while (rows.size < pageSize && subscription?.nextHistoryPage != null) {
                        currentCoroutineContext().ensureActive()
                        val current = requireNotNull(subscription)
                        val number = requireNotNull(current.nextHistoryPage)
                        val page = requireNotNull(client.releases(current.repo, pageNumber = number))
                        store.record(current, page, includePrereleases, historyPage = number)
                        subscription = store.subscription(subscriptionId)
                        rows = store.history(subscriptionId, beforeId, includePrereleases, pageSize + 1)
                    }
                } catch (failure: GitHubFailure) {
                    error = failure.explanation
                } catch (_: IOException) {
                    error = connectionError()
                } catch (_: JSONException) {
                    error = "GitHub 返回的数据无法读取，请稍后重试"
                }
                publish()
                HistoryPage(rows.take(pageSize), rows.size > pageSize || subscription?.nextHistoryPage != null, error)
            }
        }

    suspend fun refresh(background: Boolean = false) = withContext(Dispatchers.IO) {
        if (!refreshLock.tryLock()) return@withContext
        try {
            if (background && !preferences().background) return@withContext
            val subscriptions = store.subscriptions()
            if (subscriptions.isEmpty()) return@withContext
            stateLock.withLock {
                refreshing = true
                publishLocked()
            }
            var successes = 0
            var failures = 0
            var failure: String? = null
            val configuration = preferences()
            try {
                val client = client(configuration)
                for (subscription in subscriptions) {
                    currentCoroutineContext().ensureActive()
                    if (background && !preferences().background) break
                    try {
                        val page = client.releases(subscription.repo, subscription.etag)
                        if (page != null) {
                            val updates = store.record(subscription, page, preferences().prereleases)
                            if (preferences().notifications) {
                                updates.sortedBy { it.publishedAt }.forEach { showUpdateNotification(app, subscription.repo, it) }
                            }
                        }
                        successes++
                    } catch (error: GitHubFailure) {
                        failures++
                        if (failure == null || error.stopBatch) failure = error.explanation
                        if (error.stopBatch) break
                    } catch (_: IOException) {
                        failures++
                        if (failure == null) failure = connectionError(configuration)
                    } catch (_: JSONException) {
                        failures++
                        if (failure == null) failure = "GitHub 返回的数据无法读取，请稍后重试"
                    }
                }
            } catch (error: GitHubFailure) {
                failure = error.explanation
            }
            stateLock.withLock {
                prefs.edit().apply {
                    if (successes > 0) putLong("checked_at", System.currentTimeMillis())
                    putInt("last_check_total", subscriptions.size)
                    putInt("last_check_succeeded", successes)
                    putInt("last_check_failed", failures)
                    putString("last_check_error", failure)
                }.apply()
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    stateLock.withLock {
                        refreshing = false
                        publishLocked()
                    }
                }
            } finally {
                refreshLock.unlock()
            }
        }
    }

    suspend fun setBackground(value: Boolean) = change { putBoolean("background", value) }
    suspend fun setInterval(value: Int) = change { putInt("interval", value) }
    suspend fun setPrereleases(value: Boolean) = change { putBoolean("prereleases", value) }
    suspend fun setNotifications(value: Boolean) = change { putBoolean("notifications", value) }
    suspend fun setAppearance(value: String) = change { putString("appearance", value) }

    suspend fun setProxy(input: String): String? {
        val value = input.trim()
        val proxy = if (value.isEmpty()) "" else ProxyAddress.parse(value)?.value
            ?: return "请输入 HTTP 代理的地址和端口，例如 http://proxy.example.com:7890"
        change { putString("proxy", proxy) }
        return null
    }

    suspend fun setToken(input: String): String? = withContext(Dispatchers.IO) {
        val token = input.trim()
        if (token.isNotEmpty() && !token.matches(Regex("[A-Za-z0-9_]{1,512}"))) {
            return@withContext "令牌格式无效，请粘贴完整的 GitHub Token"
        }
        try {
            vault.save(token)
        } catch (_: Exception) {
            return@withContext "无法更新本机加密存储，请稍后重试"
        }
        publish()
        null
    }

    private suspend fun change(edit: SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        stateLock.withLock {
            val old = preferences()
            prefs.edit().apply(edit).apply()
            val new = preferences()
            if (old.background != new.background || old.intervalHours != new.intervalHours) scheduleChecks(app, new)
            publishLocked()
        }
    }

    private suspend fun publish() = stateLock.withLock { publishLocked() }

    private fun publishLocked() {
        val configuration = preferences()
        val checkTotal = prefs.getInt("last_check_total", 0)
        val checkResult = if (checkTotal == 0) null else CheckResult(
            checkTotal, prefs.getInt("last_check_succeeded", 0), prefs.getInt("last_check_failed", 0),
            prefs.getString("last_check_error", null),
        )
        stateFlow.value = TrackerState(
            store.subscriptions(), store.latest(configuration.prereleases), configuration,
            prefs.getLong("checked_at", 0L), refreshing, checkResult,
        )
    }

    private fun client(configuration: Preferences = preferences()): GitHubClient {
        val token = try {
            vault.read()
        } catch (_: Exception) {
            throw GitHubFailure("无法读取 GitHub Token，请重新填写或清除", stopBatch = true)
        }
        return GitHubClient(ProxyAddress.parse(configuration.proxy), token)
    }

    private fun connectionError(configuration: Preferences = preferences()) =
        if (configuration.proxy.isEmpty()) "无法连接 GitHub，请检查网络或配置 HTTP 代理"
        else "无法通过 HTTP 代理连接 GitHub，请检查代理地址和网络"
}

private class TokenVault(private val prefs: SharedPreferences) {
    val configured get() = prefs.contains("encrypted_token")

    @Synchronized
    fun save(value: String) {
        if (value.isEmpty()) {
            check(prefs.edit().remove("encrypted_token").commit())
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted_token", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
    }

    @Synchronized
    fun read(): String? {
        val stored = prefs.getString("encrypted_token", null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOf(12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }

    private fun key(create: Boolean): SecretKey {
        val alias = "github-token"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
