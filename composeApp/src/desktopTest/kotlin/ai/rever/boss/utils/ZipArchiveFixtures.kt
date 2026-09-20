package ai.rever.boss.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Hand-written zip bytes for the extraction tests in this package.
 *
 * WHY raw bytes instead of ZipOutputStream: the fixtures needed here - an entry whose unix
 * mode marks it as a symbolic link, and an entry whose central directory understates its
 * real uncompressed size - cannot be produced through ZipOutputStream, because that api
 * fixes every piece of metadata to the bytes actually written. The bytes are assembled
 * with little-endian field writes per the PKWARE appnote.
 */
internal object ZipArchiveFixtures {
    /**
     * A one-entry STORED archive whose entry is a symbolic link named [name] with [content]
     * as its stored target - the mode bits a real archive uses for framework links like
     * `Versions/Current`.
     */
    fun symlinkModeEntry(
        dest: File,
        name: String,
        content: String = "/outside/target",
    ): File = writeStored(dest, name, content.toByteArray(), unixMode = 0xA1FF)

    /**
     * A one-entry DEFLATED archive that really inflates to [actualBytes] of repeated data
     * while its central directory declares only [declaredSize] - the lying-metadata shape
     * the copy-time caps exist for, since a pre-scan that trusts the directory would wave
     * it through.
     */
    fun deflatedEntryWithUnderstatedSize(
        dest: File,
        name: String,
        actualBytes: Int,
        declaredSize: Long = 1L,
    ): File {
        val raw = ByteArray(actualBytes) { 'x'.code.toByte() }
        val compressed =
            ByteArrayOutputStream().let { buffer ->
                // Zip entries carry RAW deflate bytes - JDK's ZipFile inflates with nowrap,
                // so the compressor must skip the zlib wrapper a default Deflater emits.
                DeflaterOutputStream(buffer, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { deflater ->
                    deflater.write(raw)
                }
                buffer.toByteArray()
            }
        val crc = CRC32().apply { update(raw) }.value
        val nameBytes = name.toByteArray()
        dest.outputStream().use { out ->
            val localOffset = 0
            out.localHeader(nameBytes, compressed, crc, actualBytes.toLong(), method = 8)
            out.write(compressed)
            val centralOffset = 30 + nameBytes.size + compressed.size
            out.centralHeader(
                nameBytes,
                crc = crc,
                compressedSize = compressed.size.toLong(),
                declaredSize = declaredSize,
                method = 8,
                unixMode = 0,
                localOffset = localOffset,
            )
            out.endOfCentralDirectory(centralOffset = centralOffset, nameBytes = nameBytes)
        }
        return dest
    }

    private fun writeStored(
        dest: File,
        name: String,
        content: ByteArray,
        unixMode: Int,
    ): File {
        val crc = CRC32().apply { update(content) }.value
        val nameBytes = name.toByteArray()
        dest.outputStream().use { out ->
            out.localHeader(nameBytes, content, crc, content.size.toLong(), method = 0)
            out.write(content)
            val centralOffset = 30 + nameBytes.size + content.size
            out.centralHeader(
                nameBytes,
                crc = crc,
                compressedSize = content.size.toLong(),
                declaredSize = content.size.toLong(),
                method = 0,
                unixMode = unixMode,
                localOffset = 0,
            )
            out.endOfCentralDirectory(centralOffset = centralOffset, nameBytes = nameBytes)
        }
        return dest
    }

    private fun OutputStream.localHeader(
        nameBytes: ByteArray,
        content: ByteArray,
        crc: Long,
        size: Long,
        method: Int,
    ) {
        le32(0x04034b50L)
        le16(20)
        le16(0)
        le16(method)
        le16(0)
        le16(0x21)
        le32(crc)
        le32(content.size.toLong())
        le32(size)
        le16(nameBytes.size)
        le16(0)
        write(nameBytes)
    }

    private fun OutputStream.centralHeader(
        nameBytes: ByteArray,
        crc: Long,
        compressedSize: Long,
        declaredSize: Long,
        method: Int,
        unixMode: Int,
        localOffset: Int,
    ) {
        le32(0x02014b50L)
        le16(0x031E)
        le16(20)
        le16(0)
        le16(method)
        le16(0)
        le16(0x21)
        le32(crc)
        le32(compressedSize)
        le32(declaredSize)
        le16(nameBytes.size)
        le16(0)
        le16(0)
        le16(0)
        le16(0)
        le32((unixMode.toLong() and 0xFFFFL) shl 16)
        le32(localOffset.toLong())
        write(nameBytes)
    }

    private fun OutputStream.endOfCentralDirectory(
        centralOffset: Int,
        nameBytes: ByteArray,
    ) {
        val centralSize = 46 + nameBytes.size
        le32(0x06054b50L)
        le16(0)
        le16(0)
        le16(1)
        le16(1)
        le32(centralSize.toLong())
        le32(centralOffset.toLong())
        le16(0)
    }

    private fun OutputStream.le16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun OutputStream.le32(value: Long) {
        for (shift in 0 until 4) {
            write(((value shr (8 * shift)) and 0xFFL).toInt())
        }
    }
}
