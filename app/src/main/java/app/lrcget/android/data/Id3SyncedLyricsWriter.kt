package app.lrcget.android.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Id3SyncedLyricsWriter {
    fun writeSyncedLyrics(mp3Bytes: ByteArray, lrcText: String): ByteArray {
        val lines = parseLrc(lrcText)
        require(lines.isNotEmpty()) { "No timestamped lyrics to embed" }

        val existing = readExistingTag(mp3Bytes)
        val audioStart = existing?.endOffset ?: 0
        val version = existing?.majorVersion?.takeIf { it == 3 || it == 4 } ?: 3
        val keptFrames = existing?.framesWithoutSyncedLyrics() ?: byteArrayOf()
        val syncedLyricsFrame = buildSyncedLyricsFrame(lines, version)
        val tagBody = keptFrames + syncedLyricsFrame
        val header = byteArrayOf(
            'I'.code.toByte(),
            'D'.code.toByte(),
            '3'.code.toByte(),
            version.toByte(),
            0,
            0
        ) + syncSafe(tagBody.size)

        return header + tagBody + mp3Bytes.copyOfRange(audioStart, mp3Bytes.size)
    }

    private fun readExistingTag(bytes: ByteArray): ExistingTag? {
        if (bytes.size < 10) return null
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return null

        val majorVersion = bytes[3].toInt()
        if (majorVersion != 3 && majorVersion != 4) return null

        val size = unsyncSafe(bytes, 6)
        val endOffset = 10 + size
        if (endOffset > bytes.size) return null

        return ExistingTag(
            majorVersion = majorVersion,
            frames = bytes.copyOfRange(10, endOffset),
            endOffset = endOffset
        )
    }

    private fun ExistingTag.framesWithoutSyncedLyrics(): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0

        while (offset + 10 <= frames.size) {
            val id = frames.copyOfRange(offset, offset + 4).toString(Charsets.ISO_8859_1)
            if (id.any { it.code == 0 }) break

            val size = if (majorVersion == 4) unsyncSafe(frames, offset + 4) else bigEndianInt(frames, offset + 4)
            if (size <= 0 || offset + 10 + size > frames.size) break

            val frame = frames.copyOfRange(offset, offset + 10 + size)
            if (id != "SYLT" && id != "USLT") {
                output.write(frame)
            }
            offset += 10 + size
        }

        return output.toByteArray()
    }

    private fun buildSyncedLyricsFrame(lines: List<SyncedLine>, version: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(1)
        payload.write("eng".toByteArray(Charsets.ISO_8859_1))
        payload.write(2)
        payload.write(1)
        payload.write(byteArrayOf((-1).toByte(), (-2).toByte(), 0, 0))

        lines.forEach { line ->
            payload.write(byteArrayOf((-1).toByte(), (-2).toByte()))
            payload.write(line.text.toByteArray(Charsets.UTF_16LE))
            payload.write(byteArrayOf(0, 0))
            payload.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(line.timeMs).array())
        }

        val payloadBytes = payload.toByteArray()
        val sizeBytes = if (version == 4) syncSafe(payloadBytes.size) else intBytes(payloadBytes.size)
        return "SYLT".toByteArray(Charsets.ISO_8859_1) + sizeBytes + byteArrayOf(0, 0) + payloadBytes
    }

    private fun parseLrc(lrcText: String): List<SyncedLine> {
        val tagPattern = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        return lrcText.lineSequence().flatMap { rawLine ->
            val matches = tagPattern.findAll(rawLine).toList()
            val text = rawLine.replace(tagPattern, "").trim()
            if (matches.isEmpty()) {
                emptySequence<SyncedLine>()
            } else {
                matches.asSequence().mapNotNull { match ->
                    val minutes = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val seconds = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    val fraction = match.groupValues[3].ifBlank { "0" }
                    val millis = when (fraction.length) {
                        1 -> fraction.toInt() * 100
                        2 -> fraction.toInt() * 10
                        else -> fraction.take(3).padEnd(3, '0').toInt()
                    }
                    SyncedLine(((minutes * 60 + seconds) * 1000) + millis, text)
                }
            }
        }.filter { it.text.isNotBlank() }
            .sortedBy { it.timeMs }
            .toList()
    }

    private fun syncSafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte()
    )

    private fun unsyncSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun intBytes(size: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array()

    private data class ExistingTag(
        val majorVersion: Int,
        val frames: ByteArray,
        val endOffset: Int
    )

    private data class SyncedLine(
        val timeMs: Int,
        val text: String
    )
}
