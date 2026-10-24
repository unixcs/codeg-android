package app.codeg.android.core.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Route-name parity between what [CodegClient] sends and what the codeg server
 * registers under the `/api` prefix.
 *
 * The server 404/501s unknown API paths (`api_not_found` → 501 `not_implemented`),
 * and every caller folds that into a generic failure — the mid-run insert feature
 * shipped broken for months because one route name carried an `acp_` prefix the
 * server never had, and the 501 text defeated the caller's `no active turn`
 * fallback. This test prevents that class of bug: it extracts every
 * `send("…")` literal from the REAL `CodegClient.kt` source (the test runs with
 * the module directory as the working directory; if a future harness changes
 * that, [locateCodegClientSource] walks up) and asserts each name exists in the
 * server route snapshot (`tools/_server_routes_snapshot.txt`).
 *
 * The snapshot is generated from the server repo by `tools/route-parity.sh -g`
 * (router.rs + cli_run/http.rs at the recorded commit). When codeg adds routes,
 * regenerate the snapshot and extend the Android calls in the same change.
 */
class RouteParityTest {

    private fun locateCodegClientSource(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = dir?.resolve(
                "src/main/java/app/codeg/android/core/network/CodegClient.kt",
            )
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("CodegClient.kt not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun locateSnapshot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = dir?.resolve("tools/_server_routes_snapshot.txt")
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("_server_routes_snapshot.txt not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Same extraction the shell script does: every `send("<name>")` literal. */
    private fun extractClientRoutes(source: String): Set<String> =
        Regex("""send\("([a-z0-9_]+)"""").findAll(source).map { it.groupValues[1] }.toSet()

    private fun loadServerSnapshot(): Set<String> =
        locateSnapshot().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Test
    fun `every CodegClient route exists on the server`() {
        val client = extractClientRoutes(locateCodegClientSource().readText())
        assertTrue("no routes extracted from CodegClient.kt — extraction regex broken", client.isNotEmpty())
        val server = loadServerSnapshot()
        assertTrue("server snapshot looks truncated", server.size > 500)
        val unknown = client - server
        assertTrue(
            "CodegClient calls routes the server does not register (they would 501 at " +
                "runtime); fix the name in CodegClient.kt or regenerate the snapshot " +
                "with tools/route-parity.sh -g. Unknown: $unknown",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `the two batch-v122 fixed routes stay correct`() {
        // Regression anchors for the routes this batch got wrong (and fixed):
        // a wrong name here defeated the insert fallback and blanked the expert
        // sheet, because the 501 text matched neither caller's recovery branch.
        val server = loadServerSnapshot()
        assertTrue("'submit_session_feedback' missing from server snapshot", "submit_session_feedback" in server)
        assertTrue("'experts_list' missing from server snapshot", "experts_list" in server)
        assertTrue(
            "'experts_list_all_install_statuses' missing from server snapshot",
            "experts_list_all_install_statuses" in server,
        )
    }
}
