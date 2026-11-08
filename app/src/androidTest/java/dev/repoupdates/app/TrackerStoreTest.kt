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
        assertFalse(store.feed(false).single().unread)
        val subscription = store.subscriptions().single()

        val new = release(101)
        val older = release(99)
        assertEquals(listOf(new), store.record(subscription, page(new, release(100), older), false))
        assertEquals(listOf(101L), store.feed(false).filter { it.unread }.map { it.id })
        assertEquals("内容", store.open(101)?.notes)
        assertTrue(store.record(subscription, page(new, release(100), older), false).isEmpty())
        assertFalse(store.feed(false).any { it.unread })

        store.remove(subscription.id)
        assertTrue(store.feed(false).isEmpty())
        assertTrue(store.record(subscription, page(new), false).isEmpty())
    }

    @Test fun prereleaseCanPromoteToAStableUpdate() = withStore { store ->
        store.add(GitHubRepository(12, repo), page(release(100)))
        val subscription = store.subscriptions().single()
        val preview = release(101).copy(prerelease = true)
        assertTrue(store.record(subscription, page(preview), false).isEmpty())
        assertEquals(listOf(100L), store.feed(false).map { it.id })
        assertFalse(store.feed(true).first().unread)
        val stable = preview.copy(prerelease = false)
        assertEquals(listOf(stable), store.record(subscription, page(stable), false))
        assertTrue(store.feed(false).first().unread)
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
