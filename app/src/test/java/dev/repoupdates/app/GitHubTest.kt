package dev.repoupdates.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubTest {
    @Test fun recognizesPublicRepositoryLinksAndShorthand() {
        val expected = RepoName("Compose-Miuix-UI", "miuix")
        assertEquals(expected, RepoName.parse("Compose-Miuix-UI/miuix"))
        assertEquals(expected, RepoName.parse("https://github.com/Compose-Miuix-UI/miuix.git"))
        assertEquals(expected, RepoName.parse("github.com/Compose-Miuix-UI/miuix/releases/latest"))
        assertEquals("compose-miuix-ui/miuix", expected.key)
        assertNull(RepoName.parse("https://github.com.example.com/a/b"))
        assertNull(RepoName.parse("https://name@github.com/a/b"))
        assertNull(RepoName.parse("https://github.com/a/b/issues"))
        assertNull(RepoName.parse("https://github.com/a/.."))
        assertNull(RepoName.parse("//github.com/a/b?next=https://other.test"))
    }

    @Test fun validatesHttpProxyAsAnAddressAndPort() {
        assertEquals("http://proxy.example.com:7890", ProxyAddress.parse("proxy.example.com:7890")?.value)
        assertEquals("http://[::1]:8080", ProxyAddress.parse("http://[::1]:8080/")?.value)
        assertNull(ProxyAddress.parse("https://proxy.example.com:7890"))
        assertNull(ProxyAddress.parse("http://proxy.example.com"))
        assertNull(ProxyAddress.parse("http://proxy.example.com:65536"))
        assertNull(ProxyAddress.parse("http://name@proxy.example.com:7890"))
        assertNull(ProxyAddress.parse("http://proxy.example.com:7890/mirror"))
    }

    @Test fun readsOnlyTheNextPageFromGitHubPaginationLinks() {
        val link = """<https://api.github.com/repos/test/repo/releases?per_page=100&page=2>; rel="next", <https://api.github.com/repos/test/repo/releases?per_page=100&page=8>; rel="last" """
        assertEquals(2, nextReleasePage(listOf(link), 1))
        assertNull(nextReleasePage(listOf(link), 2))
        assertNull(nextReleasePage(listOf("""<https://api.github.com/releases?page=1>; rel="prev" """), 2))
        assertNull(nextReleasePage(emptyList(), 1))
    }

    @Test fun preservesVersionNotesAndExcludesDrafts() {
        val repo = RepoName("example", "project")
        val json = JSONObject()
            .put("id", 42).put("tag_name", "v2.0.0")
            .put("published_at", "2026-11-08T01:18:00Z")
            .put("body", "## 新增\n\n- 支持 **离线同步**")
            .put("prerelease", true)
        val release = requireNotNull(GitHubRelease.fromJson(json, repo))
        assertEquals("v2.0.0", release.tag)
        assertEquals("支持 离线同步", release.summary)
        assertEquals("## 新增\n\n- 支持 **离线同步**", release.notes)
        assertEquals("https://github.com/example/project/releases/tag/v2.0.0", release.webUrl)
        assertTrue(release.prerelease)
        assertNull(GitHubRelease.fromJson(json.put("draft", true), repo))
    }
}
