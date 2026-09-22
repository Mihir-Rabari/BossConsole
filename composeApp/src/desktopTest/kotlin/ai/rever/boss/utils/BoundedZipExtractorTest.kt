package ai.rever.boss.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial extraction tests for [BoundedZipExtractor].
 *
 * WHY: the engine bundle is fetched over the network and inflated into a directory whose
 * binaries the app then executes. The catalog checksum authenticates WHICH archive arrives;
 * these tests prove the extractor itself contains whatever arrives - a `../` or absolute
 * entry name never escapes the root, a symlink entry is never materialized, and a bomb is
 * cut off at its caps rather than at the disk, whether its central directory tells the
 * truth or lies about its sizes.
 */
class BoundedZipExtractorTest {
    @TempDir
    lateinit var root: File

    private val extractDir: File get() = File(root, "engine")

    private val tinyLimits =
        BoundedZipExtractor.Limits(
            maxEntries = 2,
            maxTotalUncompressedBytes = 64,
            maxEntryUncompressedBytes = 32,
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val zip = File(root, "archive.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    private fun extract(
        zip: File,
        limits: BoundedZipExtractor.Limits = tinyLimits,
        onFile: (ZipEntry, Path) -> Unit = { _, _ -> },
    ) {
        BoundedZipExtractor.extract(zip.toPath(), extractDir.toPath(), limits, onFile)
    }

    @Test
    fun `nested files and directories extract with names preserved`() {
        val seen = mutableListOf<String>()

        extract(zipOf("a/b/c.txt" to "hello".toByteArray(), "top.txt" to "x".toByteArray())) { entry, _ ->
            seen += entry.name
        }

        assertTrue(File(extractDir, "a/b").isDirectory)
        assertTrue(File(extractDir, "a/b/c.txt").readText() == "hello")
        assertTrue(File(extractDir, "top.txt").isFile)
        assertTrue(seen == listOf("a/b/c.txt", "top.txt"), "every file must be reported, in order")
    }

    @Test
    fun `a dot-dot entry is refused and never reaches outside the root`() {
        val zip =
            zipOf(
                "inside.txt" to "ok".toByteArray(),
                "../escape.txt" to "pwned".toByteArray(),
            )

        assertFailsWith<SecurityException> { extract(zip) }

        assertFalse(File(root, "escape.txt").exists(), "nothing may be written outside the root")
        assertFalse(File(extractDir, "escape.txt").exists())
    }

    @Test
    fun `an absolute-path entry is refused`() {
        val canary = "/BOSS-zip-slip-canary.txt"
        try {
            val zip = zipOf(canary to "pwned".toByteArray())

            assertFailsWith<SecurityException> { extract(zip) }

            assertFalse(File(canary).exists(), "an absolute entry name must not escape the root")
        } finally {
            File(canary).delete()
        }
    }

    @Test
    fun `a symlink-mode entry is refused`() {
        val zip = ZipArchiveFixtures.symlinkModeEntry(File(root, "symlink.zip"), "innocent.txt")

        assertFailsWith<SecurityException> { extract(zip) }

        assertFalse(File(extractDir, "innocent.txt").exists(), "no link and no file may land")
    }

    @Test
    fun `more entries than the limit are refused before extraction starts`() {
        val zip =
            zipOf(
                "a.txt" to "1".toByteArray(),
                "b.txt" to "2".toByteArray(),
                "c.txt" to "3".toByteArray(),
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a declared total beyond the limit is refused before extraction starts`() {
        val limits =
            BoundedZipExtractor.Limits(
                maxEntries = 2,
                maxTotalUncompressedBytes = 64,
                maxEntryUncompressedBytes = 64,
            )
        val zip = zipOf("a.txt" to ByteArray(40), "b.txt" to ByteArray(40))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip, limits) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a single entry larger than the per-entry cap is refused`() {
        val zip = zipOf("big.bin" to ByteArray(40))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a lying central directory cannot smuggle bytes past the per-entry cap`() {
        val zip =
            ZipArchiveFixtures.deflatedEntryWithUnderstatedSize(
                File(root, "understated.zip"),
                "smuggled.bin",
                actualBytes = 4096,
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        val partial = File(extractDir, "smuggled.bin")
        assertTrue(!partial.exists() || partial.length() <= 32, "the crossing chunk is never written")
    }

    @Test
    fun `a lying central directory cannot smuggle bytes past the total cap`() {
        val limits =
            BoundedZipExtractor.Limits(
                maxEntries = 1,
                maxTotalUncompressedBytes = 2048,
                maxEntryUncompressedBytes = 100_000,
            )
        val zip =
            ZipArchiveFixtures.deflatedEntryWithUnderstatedSize(
                File(root, "understated-total.zip"),
                "smuggled.bin",
                actualBytes = 4096,
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip, limits) }

        val partial = File(extractDir, "smuggled.bin")
        assertTrue(!partial.exists() || partial.length() <= 2048, "the crossing chunk is never written")
    }

    @Test
    fun `the declared pre-scan gates archives for extractors that cannot cap`() {
        val zip = zipOf("a.txt" to ByteArray(40), "b.txt" to ByteArray(40))
        val refusingLimits = BoundedZipExtractor.Limits(2, 64, 64)
        val acceptingLimits = BoundedZipExtractor.Limits(2, 80, 64)

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> {
            BoundedZipExtractor.verifyDeclaredWithinLimits(zip.toPath(), refusingLimits)
        }
        BoundedZipExtractor.verifyDeclaredWithinLimits(zip.toPath(), acceptingLimits)
    }

    @Test
    fun `the pre-scan refuses escaping entry names for extractors that cannot contain`() {
        val zip =
            zipOf(
                "inside.txt" to "ok".toByteArray(),
                "../escape.txt" to "pwned".toByteArray(),
            )

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath())
        }

        assertFalse(File(root, "escape.txt").exists(), "the pre-scan writes nothing at all")
    }

    @Test
    fun `the pre-scan refuses symlink entries for extractors that cannot contain`() {
        val zip = ZipArchiveFixtures.symlinkModeEntry(File(root, "symlink.zip"), "innocent.txt")

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath())
        }
    }

    @Test
    fun `the mac pre-scan permits an internal framework Versions Current link`() {
        val zip =
            ZipArchiveFixtures.symlinkModeEntry(
                File(root, "framework-link.zip"),
                "Chromium Framework.framework/Versions/Current",
                "A",
            )

        BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath(), allowFrameworkSymlinks = true)
    }

    @Test
    fun `the mac pre-scan refuses a framework link escaping the extraction root`() {
        val zip =
            ZipArchiveFixtures.symlinkModeEntry(
                File(root, "escaping-link.zip"),
                "Chromium Framework.framework/Versions/Current",
                "../../../../outside",
            )

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(
                zip.toPath(),
                extractDir.toPath(),
                allowFrameworkSymlinks = true,
            )
        }
    }
}
