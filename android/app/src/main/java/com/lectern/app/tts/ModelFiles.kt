package com.lectern.app.tts

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a KittenTTS 2 nano language (model about 17 MB, voices about 2 MB) from Hugging
 * Face once, into the app's private storage. Pinned to a commit so a model update can't
 * break the app; the web version uses the same files.
 */
class ModelFiles(context: Context) {

    companion object {
        private const val REPO = "https://huggingface.co/KittenML/kitten-tts-2-nano/resolve/0843ae614780b04e4260c91b601e374c4b81250d"
    }

    private val dir = File(context.filesDir, "kitten").apply { mkdirs() }

    init {
        // The Kokoro model of earlier versions (92 MB) is no longer used.
        File(context.filesDir, "kokoro").takeIf { it.exists() }?.deleteRecursively()
    }

    fun model(lang: String) = File(dir, "kitten_tts_2_nano_$lang.onnx")
    fun voices(lang: String) = File(dir, "voices_$lang.npz")

    fun has(lang: String) = listOf(model(lang), voices(lang)).all { it.isFile && it.length() > 0 }

    fun ensure(lang: String, onProgress: (loaded: Long, total: Long) -> Unit) {
        val voices = voices(lang)
        if (!voices.isFile || voices.length() == 0L) download("$REPO/${voices.name}", voices) { _, _ -> }
        val model = model(lang)
        if (!model.isFile || model.length() == 0L) download("$REPO/${model.name}", model, onProgress)
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var conn = URL(url).openConnection() as HttpURLConnection
        // Hugging Face redirects to a CDN; follow across hosts manually.
        var redirects = 0
        while (true) {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Lectern-Android")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), loc).openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw java.io.IOException("Download failed (HTTP $code)")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var loaded = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    loaded += n
                    if (loaded - lastReport > 512 * 1024) {
                        lastReport = loaded
                        onProgress(loaded, total)
                    }
                }
                onProgress(loaded, total)
            }
        }
        conn.disconnect()
        if (total > 0 && part.length() != total) {
            part.delete()
            throw java.io.IOException("The download was interrupted. Check your connection and try again.")
        }
        if (!part.renameTo(dest)) throw java.io.IOException("Could not save ${dest.name}")
    }
}
