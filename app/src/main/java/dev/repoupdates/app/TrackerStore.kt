package dev.repoupdates.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

internal data class Subscription(val id: Long, val repo: RepoName, val etag: String?, val nextHistoryPage: Int?)
internal data class FeedItem(
    val id: Long,
    val repo: RepoName,
    val tag: String,
    val publishedAt: Long,
    val prerelease: Boolean,
    val unread: Boolean,
    val summary: String,
    val subscriptionId: Long,
)
internal data class ReleaseDetail(val item: FeedItem, val notes: String, val webUrl: String)

internal class TrackerStore(context: Context, name: String = "repo-updates.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE subscriptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT NOT NULL UNIQUE,
                github_id INTEGER NOT NULL UNIQUE,
                owner TEXT NOT NULL,
                name TEXT NOT NULL,
                etag TEXT,
                next_history_page INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE releases (
                id INTEGER PRIMARY KEY,
                subscription INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
                tag TEXT NOT NULL,
                published INTEGER NOT NULL,
                prerelease INTEGER NOT NULL,
                unread INTEGER NOT NULL,
                summary TEXT NOT NULL,
                notes TEXT NOT NULL,
                url TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(historyIndex)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 1 kept the first response but did not record whether GitHub has more pages.
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN next_history_page INTEGER DEFAULT 1")
            db.execSQL(historyIndex)
        }
    }

    fun subscriptions(): List<Subscription> = readableDatabase.rawQuery(
        "$subscriptionQuery ORDER BY id DESC", null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.subscription()) }
    }

    fun subscription(id: Long): Subscription? = readableDatabase.rawQuery(
        "$subscriptionQuery WHERE id=?", arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.subscription() else null }

    fun add(repository: GitHubRepository, page: ReleasePage): Boolean = writableDatabase.transaction {
        val repo = repository.repo
        val rowId = insertWithOnConflict("subscriptions", null, ContentValues().apply {
            put("key", repo.key)
            put("github_id", repository.id)
            put("owner", repo.owner)
            put("name", repo.name)
            put("etag", page.etag)
            put("next_history_page", page.nextPage)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowId == -1L) return@transaction false
        page.releases.forEach { insertOrThrow("releases", null, releaseValues(rowId, it, unread = false)) }
        true
    }

    fun record(
        subscription: Subscription,
        page: ReleasePage,
        includePrereleases: Boolean,
        historyPage: Int? = null,
    ): List<GitHubRelease> =
        writableDatabase.transaction {
            val row = subscription.id.toString()
            val values = ContentValues().apply {
                put("next_history_page", page.nextPage)
                if (historyPage == null || historyPage == 1) put("etag", page.etag)
            }
            val updated = if (historyPage == null) {
                update("subscriptions", values, "id=?", arrayOf(row))
            } else {
                // A concurrent check can reset the continuation to the latest first page.
                update("subscriptions", values, "id=? AND next_history_page=? AND COALESCE(etag, '')=?",
                    arrayOf(row, historyPage.toString(), subscription.etag.orEmpty()))
            }
            if (updated == 0) return@transaction emptyList()
            val latestPublishedAt = rawQuery("SELECT MAX(published) FROM releases WHERE subscription=?", arrayOf(row)).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
            val existing = rawQuery("SELECT id, prerelease, unread FROM releases WHERE subscription=?", arrayOf(row)).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(cursor.getLong(0), SeenRelease(cursor.getInt(1) != 0, cursor.getInt(2) != 0))
                }
            }
            buildList {
                for (release in page.releases) {
                    val seen = existing[release.id]
                    val firstSeen = seen == null && (latestPublishedAt == null || release.publishedAt >= latestPublishedAt)
                    val promoted = seen?.prerelease == true && !release.prerelease
                    val newUpdate = historyPage == null && (firstSeen || promoted) && (!release.prerelease || includePrereleases)
                    val releaseRow = releaseValues(subscription.id, release, unread = newUpdate || seen?.unread == true)
                    if (seen == null) {
                        insertOrThrow("releases", null, releaseRow)
                    } else {
                        update("releases", releaseRow, "id=? AND subscription=?", arrayOf(release.id.toString(), row))
                    }
                    if (newUpdate) add(release)
                }
            }
        }

    fun remove(id: Long) {
        writableDatabase.delete("subscriptions", "id=?", arrayOf(id.toString()))
    }

    fun latest(includePrereleases: Boolean): List<FeedItem> {
        val filter = if (includePrereleases) "" else "AND candidate.prerelease=0"
        return queryFeed(
            """SELECT $feedColumns FROM subscriptions s JOIN releases r ON r.id=(
                SELECT candidate.id FROM releases candidate WHERE candidate.subscription=s.id $filter
                ORDER BY candidate.published DESC, candidate.id DESC LIMIT 1
            ) ORDER BY r.published DESC, r.id DESC""".trimIndent(), null,
        )
    }

    fun history(subscriptionId: Long, beforeId: Long?, includePrereleases: Boolean, limit: Int): List<FeedItem> {
        val filter = if (includePrereleases) "" else "AND r.prerelease=0"
        val before = if (beforeId == null) "" else
            "AND (r.published, r.id) < ((SELECT published FROM releases WHERE id=? AND subscription=r.subscription), ?)"
        val args = buildList {
            add(subscriptionId.toString())
            if (beforeId != null) repeat(2) { add(beforeId.toString()) }
            add(limit.toString())
        }
        return queryFeed(
            "SELECT $feedColumns $join WHERE r.subscription=? $filter $before ORDER BY r.published DESC, r.id DESC LIMIT ?",
            args.toTypedArray(),
        )
    }

    private fun queryFeed(sql: String, args: Array<String>?): List<FeedItem> =
        readableDatabase.rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.feedItem()) } }

    fun open(id: Long): ReleaseDetail? = writableDatabase.transaction {
        val selection = arrayOf(id.toString())
        update("releases", ContentValues().apply { put("unread", 0) }, "id=? AND unread=1", selection)
        rawQuery("SELECT $feedColumns, r.notes, r.url $join WHERE r.id=?", selection).use { cursor ->
            if (cursor.moveToFirst()) ReleaseDetail(cursor.feedItem(), cursor.getString(9), cursor.getString(10)) else null
        }
    }

    private data class SeenRelease(val prerelease: Boolean, val unread: Boolean)

    private fun Cursor.subscription() = Subscription(
        getLong(0), RepoName(getString(1), getString(2)), getString(3), if (isNull(4)) null else getInt(4),
    )

    private fun Cursor.feedItem() = FeedItem(
        getLong(0), RepoName(getString(1), getString(2)), getString(3), getLong(4),
        getInt(5) != 0, getInt(6) != 0, getString(7), getLong(8),
    )

    private fun releaseValues(subscriptionId: Long, release: GitHubRelease, unread: Boolean) = ContentValues().apply {
        put("id", release.id)
        put("subscription", subscriptionId)
        put("tag", release.tag)
        put("published", release.publishedAt)
        put("prerelease", release.prerelease)
        put("unread", unread)
        put("summary", release.summary)
        put("notes", release.notes)
        put("url", release.webUrl)
    }

    private companion object {
        const val subscriptionQuery = "SELECT id, owner, name, etag, next_history_page FROM subscriptions"
        const val feedColumns = "r.id, s.owner, s.name, r.tag, r.published, r.prerelease, r.unread, r.summary, r.subscription"
        const val join = "FROM releases r JOIN subscriptions s ON r.subscription=s.id"
        const val historyIndex = "CREATE INDEX releases_by_subscription_date ON releases(subscription, published DESC, id DESC)"
    }
}
