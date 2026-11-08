package com.lectern.app.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Generated speech, one file per sentence, kept on disk so audio made ahead of time
 * (while playing, while paused, or in an earlier session) is reused instead of regenerated.
 *
 * Audio is stored as 16-bit PCM (48 KB per second). Each book/voice/speed has its own
 * folder. Folders used least recently are deleted when the total passes [maxBytes].
 * It lives in cacheDir, so Android may also clear it when storage runs low.
 */
class AudioCache(private val root: File, private val maxBytes: Long = 400L * 1024 * 1024) {

    companion object {
        /** Bump when phonemes or generation change, so old audio is not reused. */
        private const val VERSION = 1
    }

    private var key: String? = null
    private var dir: File? = null
    /** Sentence index -> number of samples, for the current key. */
    private val frames = ConcurrentHashMap<Int, Int>()

    fun keyFor(bookId: String, voice: String, speed: Float) =
        "v${VERSION}_${bookId}_${voice}_${(speed * 100).toInt()}".replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Switches to [newKey], indexing what is already on disk. */
    @Synchronized
    fun open(newKey: String) {
        if (newKey == key) return
        key = newKey
        frames.clear()
        val d = File(root, newKey).apply { mkdirs() }
        d.setLastModified(System.currentTimeMillis())
        dir = d
        d.listFiles()?.forEach { f ->
            val i = f.name.removeSuffix(".pcm").toIntOrNull()
            if (i != null && f.name.endsWith(".pcm")) frames[i] = (f.length() / 2).toInt() else f.delete()
        }
        trim()
    }

    /** Forgets the open folder (after the files were deleted). */
    @Synchronized
    fun reset() {
        key = null
        dir = null
        frames.clear()
    }

    fun has(index: Int) = frames.containsKey(index)

    /** Seconds of audio already made, counting forward from [from] until the first gap. */
    fun secondsAhead(from: Int, sampleRate: Int, gap: (Int) -> Double): Double {
        var total = 0.0
        var i = from
        while (true) {
            val n = frames[i] ?: break
            total += n.toDouble() / sampleRate + gap(i)
            i++
        }
        return total
    }

    /** First index at or after [from] that has no audio yet, or -1 past [end]. */
    fun firstMissing(from: Int, end: Int): Int {
        var i = from
        while (i < end) { if (!frames.containsKey(i)) return i; i++ }
        return -1
    }

    fun read(index: Int): FloatArray? {
        val f = File(dir ?: return null, "$index.pcm")
        if (!frames.containsKey(index) || !f.exists()) { frames.remove(index); return null }
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }

    fun write(forKey: String, index: Int, pcm: FloatArray) {
        val d: File
        synchronized(this) { if (forKey != key) return; d = dir ?: return }
        val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) buf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val tmp = File(d, "$index.tmp")
        tmp.writeBytes(buf.array())
        tmp.renameTo(File(d, "$index.pcm"))
        synchronized(this) { if (forKey == key) frames[index] = pcm.size }
    }

    /** Deletes the least recently used folders (never the open one) until under the limit. */
    @Synchronized
    fun trim() {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        for (d in dirs.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (d == dir) continue
            total -= d.listFiles()?.sumOf { it.length() } ?: 0L
            d.deleteRecursively()
        }
    }
}
