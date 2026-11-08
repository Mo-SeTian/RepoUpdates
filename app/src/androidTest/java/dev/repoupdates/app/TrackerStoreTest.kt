package dev.repoupdates.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerStoreTest {
    private val repo = RepoName("test", "repo")
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun baselineNewReleaseAndRepeatedCheckAreConsistent() = withStore { store ->
        assertTrue(store.add(GitHubRepository(12, repo), page(release(100))))
        assertFalse(store.add(GitHubRepository(12, RepoName("renamed", "repo")), page(release(100))))
        assertFalse(store.latest(false).single().unread)
        val subscription = store.subscriptions().single()

        val new = release(101)
        val older = release(99)
        assertEquals(listOf(new), store.record(subscription, page(new, release(100), older), false))
        assertEquals(listOf(101L), store.latest(false).filter { it.unread }.map { it.id })
        assertEquals("内容", store.open(101)?.notes)
        assertTrue(store.record(subscription, page(new, release(100), older), false).isEmpty())
        assertFalse(store.latest(false).any { it.unread })

        store.remove(subscription.id)
        assertTrue(store.latest(false).isEmpty())
        assertTrue(store.record(subscription, page(new), false).isEmpty())
    }

    @Test fun prereleaseCanPromoteToAStableUpdate() = withStore { store ->
        store.add(GitHubRepository(12, repo), page(release(100)))
        val subscription = store.subscriptions().single()
        val preview = release(101).copy(prerelease = true)
        assertTrue(store.record(subscription, page(preview), false).isEmpty())
        assertEquals(listOf(100L), store.latest(false).map { it.id })
        assertFalse(store.latest(true).first().unread)
        val stable = preview.copy(prerelease = false)
        assertEquals(listOf(stable), store.record(subscription, page(stable), false))
        assertTrue(store.latest(false).first().unread)
    }

    @Test fun latestHasOneVersionPerRepositoryAndHistoryHasStablePageBoundaries() = withStore { store ->
        val baseline = (1L..45).map { release(it).copy(publishedAt = (it / 2) * 1000) }
        store.add(GitHubRepository(12, repo), ReleasePage(baseline, "first", nextPage = 2))
        val other = RepoName("other", "repo")
        store.add(GitHubRepository(13, other), page(release(100)))
        assertEquals(listOf(100L, 45L), store.latest(false).map { it.id })
        val subscription = store.subscriptions().first { it.repo == repo }
        val first = store.history(subscription.id, null, false, 20)
        assertEquals((45L downTo 26L).toList(), first.map { it.id })

        store.record(subscription, page(release(46)), false)
        val second = store.history(subscription.id, first.last().id, false, 20)
        val last = store.history(subscription.id, second.last().id, false, 20)
        assertEquals((25L downTo 6L).toList(), second.map { it.id })
        assertEquals((5L downTo 1L).toList(), last.map { it.id })
        assertTrue(store.history(subscription.id, last.last().id, false, 20).isEmpty())
        assertEquals(45, (first + second + last).map { it.id }.distinct().size)
    }

    @Test fun historyCachingPreservesUnreadAndDoesNotChangeTheFirstPageEtag() = withStore { store ->
        store.add(GitHubRepository(12, repo), ReleasePage(listOf(release(100)), "first", nextPage = 2))
        val subscription = store.subscriptions().single()
        val older = ReleasePage((1L..99L).map { release(it) }, "second", nextPage = null)
        assertTrue(store.record(subscription, older, false, historyPage = 2).isEmpty())
        assertEquals("first", store.subscriptions().single().etag)
        assertNull(store.subscriptions().single().nextHistoryPage)
        assertFalse(store.history(subscription.id, null, true, 100).any { it.unread })
        assertEquals(listOf(100L), store.latest(false).map { it.id })

        store.record(subscription, ReleasePage(listOf(release(101)), "new-first", nextPage = 2), false)
        // A history request made before the check cannot overwrite its continuation or cached latest.
        store.record(subscription, older, false, historyPage = 2)
        assertEquals("new-first", store.subscriptions().single().etag)
        assertEquals(2, store.subscriptions().single().nextHistoryPage)
        assertTrue(store.latest(false).single().unread)
    }

    @Test fun latestExcludesPrereleasesBeforeChoosingEachRepositoryVersion() = withStore { store ->
        store.add(GitHubRepository(12, repo), page(release(100), release(101).copy(prerelease = true)))
        assertEquals(100L, store.latest(false).single().id)
        assertEquals(101L, store.latest(true).single().id)
    }

    @Test fun upgradesVersionOneAndKeepsTheExistingSubscriptionsAndReleases() {
        val name = "tracker-upgrade-${System.nanoTime()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("""CREATE TABLE subscriptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL UNIQUE,
                    github_id INTEGER NOT NULL UNIQUE, owner TEXT NOT NULL, name TEXT NOT NULL, etag TEXT
                )""")
                db.execSQL("""CREATE TABLE releases (
                    id INTEGER PRIMARY KEY, subscription INTEGER NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
                    tag TEXT NOT NULL, published INTEGER NOT NULL, prerelease INTEGER NOT NULL,
                    unread INTEGER NOT NULL, summary TEXT NOT NULL, notes TEXT NOT NULL, url TEXT NOT NULL
                )""")
                db.execSQL("INSERT INTO subscriptions VALUES (1, 'test/repo', 12, 'test', 'repo', 'old-etag')")
                db.execSQL("INSERT INTO releases VALUES (100, 1, 'v100', 1000, 0, 1, 'summary', 'notes', 'https://github.com/test/repo')")
                db.version = 1
            }
            TrackerStore(context, name).use { store ->
                assertEquals("old-etag", store.subscriptions().single().etag)
                assertEquals(1, store.subscriptions().single().nextHistoryPage)
                assertEquals(100L, store.latest(false).single().id)
                assertTrue(store.latest(false).single().unread)
                assertEquals("notes", store.open(100)?.notes)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun withStore(test: (TrackerStore) -> Unit) {
        val name = "tracker-test-${System.nanoTime()}.db"
        try {
            TrackerStore(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun page(vararg releases: GitHubRelease) = ReleasePage(releases.toList(), "test-etag")

    private fun release(id: Long) = GitHubRelease(
        id, "v$id", "内容", id * 1000, false, "https://github.com/test/repo/releases/tag/v$id",
    )
}
