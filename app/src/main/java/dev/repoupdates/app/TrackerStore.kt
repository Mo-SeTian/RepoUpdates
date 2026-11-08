package dev.repoupdates.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

internal data class Subscription(val id: Long, val repo: RepoName, val etag: String?)
internal data class FeedItem(
    val id: Long,
    val repo: RepoName,
    val tag: String,
    val publishedAt: Long,
    val prerelease: Boolean,
    val unread: Boolean,
    val summary: String,
)
internal data class ReleaseDetail(val item: FeedItem, val notes: String, val webUrl: String)

internal class TrackerStore(context: Context, name: String = "repo-updates.db") : SQLiteOpenHelper(context, name, null, 1) {
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
                etag TEXT
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
        db.execSQL("CREATE INDEX releases_by_subscription ON releases(subscription)")
        db.execSQL("CREATE INDEX releases_by_date ON releases(published DESC, id DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun subscriptions(): List<Subscription> = readableDatabase.rawQuery(
        "SELECT id, owner, name, etag FROM subscriptions ORDER BY id DESC", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Subscription(cursor.getLong(0), RepoName(cursor.getString(1), cursor.getString(2)), cursor.getString(3)))
        }
    }

    fun add(repository: GitHubRepository, page: ReleasePage): Boolean = writableDatabase.transaction {
        val repo = repository.repo
        val rowId = insertWithOnConflict("subscriptions", null, ContentValues().apply {
            put("key", repo.key)
            put("github_id", repository.id)
            put("owner", repo.owner)
            put("name", repo.name)
            put("etag", page.etag)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowId == -1L) return@transaction false
        page.releases.forEach { insertOrThrow("releases", null, releaseValues(rowId, it, unread = false)) }
        true
    }

    fun record(subscription: Subscription, page: ReleasePage, includePrereleases: Boolean): List<GitHubRelease> =
        writableDatabase.transaction {
            val row = subscription.id.toString()
            val updated = update("subscriptions", ContentValues().apply { put("etag", page.etag) }, "id=?", arrayOf(row))
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
                    val newUpdate = (firstSeen || promoted) && (!release.prerelease || includePrereleases)
                    val values = releaseValues(subscription.id, release, unread = newUpdate || seen?.unread == true)
                    if (seen == null) {
                        insertOrThrow("releases", null, values)
                    } else {
                        update("releases", values, "id=? AND subscription=?", arrayOf(release.id.toString(), row))
                    }
                    if (newUpdate) add(release)
                }
            }
        }

    fun remove(id: Long) {
        writableDatabase.delete("subscriptions", "id=?", arrayOf(id.toString()))
    }

    fun feed(includePrereleases: Boolean): List<FeedItem> {
        val filter = if (includePrereleases) "" else "WHERE r.prerelease=0"
        return readableDatabase.rawQuery(
            "SELECT $feedColumns $join $filter ORDER BY r.published DESC, r.id DESC", null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.feedItem()) } }
    }

    fun open(id: Long): ReleaseDetail? = writableDatabase.transaction {
        val selection = arrayOf(id.toString())
        update("releases", ContentValues().apply { put("unread", 0) }, "id=? AND unread=1", selection)
        rawQuery("SELECT $feedColumns, r.notes, r.url $join WHERE r.id=?", selection).use { cursor ->
            if (cursor.moveToFirst()) ReleaseDetail(cursor.feedItem(), cursor.getString(8), cursor.getString(9)) else null
        }
    }

    private data class SeenRelease(val prerelease: Boolean, val unread: Boolean)

    private fun Cursor.feedItem() = FeedItem(
        getLong(0), RepoName(getString(1), getString(2)), getString(3), getLong(4),
        getInt(5) != 0, getInt(6) != 0, getString(7),
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
        const val feedColumns = "r.id, s.owner, s.name, r.tag, r.published, r.prerelease, r.unread, r.summary"
        const val join = "FROM releases r JOIN subscriptions s ON r.subscription=s.id"
    }
}
