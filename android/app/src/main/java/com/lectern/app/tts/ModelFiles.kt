package com.lectern.app.tts

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the Kokoro model and voices from Hugging Face once, into the app's private storage. */
class ModelFiles(context: Context) {

    companion object {
        private const val REPO = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main"
        private const val MODEL_PATH = "onnx/model_quantized.onnx" // 8-bit, about 92 MB (same as the web CPU engine)
    }

    private val dir = File(context.filesDir, "kokoro").apply { mkdirs() }

    val model = File(dir, "model_quantized.onnx")
    fun voice(id: String) = File(dir, "voices/$id.bin")

    val hasModel get() = model.isFile && model.length() > 0

    fun ensureModel(onProgress: (loaded: Long, total: Long) -> Unit) {
        if (!hasModel) download("$REPO/$MODEL_PATH", model, onProgress)
    }

    fun ensureVoice(id: String): File {
        val f = voice(id)
        if (!f.isFile || f.length() == 0L) download("$REPO/voices/$id.bin", f) { _, _ -> }
        return f
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
