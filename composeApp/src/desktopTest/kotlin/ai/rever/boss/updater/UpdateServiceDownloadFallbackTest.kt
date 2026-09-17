package ai.rever.boss.updater

import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.sha256Of
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins that the GitHub fallback download verifies against the catalog's sha256
 * exactly like the primary path (#797):
 *
 * - a fallback body that does not match the catalog hash is discarded, not staged;
 * - a matching body stages, and the file on disk hashes to the catalog value;
 * - a catalog row without a hash (the plain GitHub-only source) still installs
 *   unverified - null stays reserved for sources that genuinely cannot describe
 *   the hash, rather than being dropped on the one path that had it in scope.
 *
 * The service's download-time fallback source is injected with a fake whose asset
 * points at a one-shot loopback HTTP server, so the whole `downloadUpdate` chain
 * runs for real: the primary URL (a dead port) fails, the fallback serves the
 * bytes, and the staged file is hashed on disk.
 */
class UpdateServiceDownloadFallbackTest {
    @TempDir
    lateinit var tempDir: Path

    private val goodBytes = "legitimate installer payload".repeat(64).toByteArray()
    private val tamperedBytes = "these bytes came from nowhere in the release pipeline".repeat(8).toByteArray()

    private val stagedAssets = mutableListOf<File>()

    @AfterEach
    fun cleanStaging() {
        stagedAssets.forEach { runCatching { it.delete() } }
        stagedAssets.clear()
    }

    /** SHA-256 of [bytes] via the production hashing util, computed off a temp file. */
    private fun sha256OfBytes(bytes: ByteArray): String =
        sha256Of(
            File(tempDir.toFile(), "sha-src-${bytes.size}").apply {
                writeBytes(bytes)
            },
        )

    /** A port with nothing listening on it, so the primary download fails fast. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * One-shot loopback HTTP server serving [body]. Exactly one connection is
     * expected - the one the fallback download makes.
     */
    private fun serveOnce(body: ByteArray): ServerSocket =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
            val server = this
            thread(isDaemon = true, name = "fallback-asset-server") {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val request = socket.getInputStream().bufferedReader()
                    while (request.readLine()?.isNotEmpty() == true) {
                        // Drain the request line and headers.
                    }
                    socket.getOutputStream().apply {
                        write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        write(body)
                        flush()
                    }
                }
            }
        }

    /** Fake fallback [UpdateSource] whose release is supplied lazily per test. */
    private class FakeFallbackSource(
        private val release: () -> GitHubRelease?,
    ) : UpdateSource {
        override val name: String = "fake-github"

        override suspend fun listReleases(): List<GitHubRelease> = listOfNotNull(release())

        override suspend fun getReleaseByTag(tag: String): GitHubRelease? = release()
    }

    /** The GitHub release the fake source serves for [version], offering [url]. */
    private fun fallbackRelease(
        version: Version,
        assetName: String,
        url: String,
        size: Long,
    ): GitHubRelease =
        GitHubRelease(
            tag_name = "v$version",
            name = "v$version",
            body = "",
            published_at = "2026-09-17T00:00:00Z",
            assets =
                listOf(
                    GitHubAsset(
                        name = assetName,
                        browser_download_url = url,
                        size = size,
                    ),
                ),
        )

    @Test
    fun `fallback discards a body that does not match the catalog sha256`() {
        val version = Version.parse("v9.9.9")!!
        var release: GitHubRelease? = null
        val service = UpdateService(gitHubSource = FakeFallbackSource { release })
        val assetName = service.getExpectedAssetName(version)
        stagedAssets += File(defaultStagingDir(), assetName)

        val server = serveOnce(tamperedBytes)
        release =
            fallbackRelease(
                version,
                assetName,
                "http://127.0.0.1:${server.localPort}/asset",
                tamperedBytes.size.toLong(),
            )

        val info =
            UpdateInfo(
                available = true,
                currentVersion = Version.parse("v9.0.0")!!,
                latestVersion = version,
                releaseNotes = "",
                downloadUrl = "http://127.0.0.1:${deadPort()}/primary-asset",
                assetSize = tamperedBytes.size.toLong(),
                assetName = assetName,
                sha256 = sha256OfBytes(goodBytes),
            )

        val path =
            runBlocking {
                service.downloadUpdate(info) {}
            }

        assertNull(path, "a mismatching fallback body must not reach the installer")
        assertFalse(
            File(defaultStagingDir(), assetName).exists(),
            "the mismatched download must be discarded from staging",
        )
        server.close()
    }

    @Test
    fun `fallback stages a body that matches the catalog sha256`() {
        val version = Version.parse("v9.9.10")!!
        var release: GitHubRelease? = null
        val service = UpdateService(gitHubSource = FakeFallbackSource { release })
        val assetName = service.getExpectedAssetName(version)
        stagedAssets += File(defaultStagingDir(), assetName)

        val server = serveOnce(goodBytes)
        release =
            fallbackRelease(
                version,
                assetName,
                "http://127.0.0.1:${server.localPort}/asset",
                goodBytes.size.toLong(),
            )

        val info =
            UpdateInfo(
                available = true,
                currentVersion = Version.parse("v9.0.0")!!,
                latestVersion = version,
                releaseNotes = "",
                downloadUrl = "http://127.0.0.1:${deadPort()}/primary-asset",
                assetSize = goodBytes.size.toLong(),
                assetName = assetName,
                sha256 = sha256OfBytes(goodBytes),
            )

        val path =
            runBlocking {
                service.downloadUpdate(info) {}
            }

        val staged = assertNotNull(path, "a fallback body matching the catalog hash must be staged")
        assertEquals(goodBytes.size.toLong(), staged.length())
        assertEquals(sha256OfBytes(goodBytes), sha256Of(staged))
        server.close()
    }

    @Test
    fun `fallback stays unverified when the catalog carries no sha256`() {
        // A plain GitHub-only source row has no hash to describe the asset with;
        // the fallback must install unverified exactly as before, not silently
        // pick a hash up from somewhere else.
        val version = Version.parse("v9.9.11")!!
        var release: GitHubRelease? = null
        val service = UpdateService(gitHubSource = FakeFallbackSource { release })
        val assetName = service.getExpectedAssetName(version)
        stagedAssets += File(defaultStagingDir(), assetName)

        val server = serveOnce(goodBytes)
        release =
            fallbackRelease(
                version,
                assetName,
                "http://127.0.0.1:${server.localPort}/asset",
                goodBytes.size.toLong(),
            )

        val info =
            UpdateInfo(
                available = true,
                currentVersion = Version.parse("v9.0.0")!!,
                latestVersion = version,
                releaseNotes = "",
                downloadUrl = "http://127.0.0.1:${deadPort()}/primary-asset",
                assetSize = goodBytes.size.toLong(),
                assetName = assetName,
                sha256 = null,
            )

        val path =
            runBlocking {
                service.downloadUpdate(info) {}
            }

        assertNotNull(path, "a hash-less catalog row must still download via the fallback")
        server.close()
    }
}
