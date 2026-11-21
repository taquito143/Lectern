package com.lectern.app.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.zip.ZipInputStream

/**
 * KittenTTS 2 nano on ONNX Runtime: one small model per language. Input: phonemes in the
 * model's IPA vocab. Output: 24 kHz mono float PCM. Follows the official pipeline
 * (kittenml/kittentts2_tiny/model.py), the same as the web version's kitten.js:
 *   tokens = [0] + phoneme ids + [10, 0]
 *   style  = the voice pack's row for that token count (pack: float32 [510, 256])
 *   model(input_ids, style, speed) -> waveform, durations per token
 *   cut the end token's frames (and the start pause, except on the first chunk),
 *   5 ms fades, 0.15 s of silence between chunks.
 */
class KittenEngine(context: Context) {

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val END_TOKEN = 10L
        private const val MAX_PHON = 350 // longest chunk the model trained on (p90)
        private const val GAP_SAMPLES = (0.15 * SAMPLE_RATE).toInt()
    }

    /** A voice: style vectors, one row of [dim] per input length. */
    class Pack(val data: FloatArray, val rows: Int, val dim: Int)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    private val packs = HashMap<String, Map<String, Pack>>()

    private val vocab: Map<Int, Long> = run {
        val json = JSONObject(context.assets.open("kitten_vocab.json").bufferedReader(Charsets.UTF_8).readText())
        val map = HashMap<Int, Long>()
        for (key in json.keys()) map[key.codePointAt(0)] = json.getLong(key)
        map
    }

    @Synchronized
    fun isLoaded(lang: String) = lang in sessions && lang in packs

    @Synchronized
    fun load(lang: String, model: File, voices: File) {
        if (lang !in sessions) {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            }
            sessions[lang] = env.createSession(model.absolutePath, opts)
        }
        if (lang !in packs) packs[lang] = readNpz(voices)
    }

    fun voice(lang: String, key: String): Pack =
        synchronized(this) { packs[lang] }?.get(key) ?: error("No voice \"$key\" for $lang")

    fun tokenize(phonemes: String): LongArray {
        val out = ArrayList<Long>(phonemes.length + 3)
        out += 0L
        var i = 0
        while (i < phonemes.length) {
            val cp = phonemes.codePointAt(i)
            val id = vocab[cp] ?: if (cp == '('.code || cp == ')'.code) vocab[','.code] else null
            if (id != null) out += id
            i += Character.charCount(cp)
        }
        out += END_TOKEN
        out += 0L
        return out.toLongArray()
    }

    /** Phonemes of one sentence -> audio. Long input is cut at spaces into chunks. */
    fun generate(lang: String, phonemes: String, voice: Pack, speed: Float): FloatArray {
        val chunks = ArrayList<String>()
        var ps = phonemes.trim()
        while (ps.length > MAX_PHON) {
            var cut = ps.lastIndexOf(' ', MAX_PHON)
            if (cut <= 0) cut = MAX_PHON
            chunks += ps.substring(0, cut)
            ps = ps.substring(cut).trim()
        }
        if (ps.isNotEmpty()) chunks += ps
        if (chunks.isEmpty()) return FloatArray(0)
        val parts = chunks.mapIndexed { i, c -> chunk(lang, c, voice, speed, keepStart = i == 0) }
        val out = FloatArray(parts.sumOf { it.size } + GAP_SAMPLES * (parts.size - 1))
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size + GAP_SAMPLES
        }
        return out
    }

    private fun chunk(lang: String, phonemes: String, voice: Pack, speed: Float, keepStart: Boolean): FloatArray {
        val tok = tokenize(phonemes)
        val row = minOf(tok.size - 1, voice.rows - 1)
        val style = voice.data.copyOfRange(row * voice.dim, (row + 1) * voice.dim)
        val (wav, dur) = run(lang, tok, style, speed)

        val total = dur.sum()
        val spf = maxOf(1L, Math.round(wav.size.toDouble() / maxOf(total, 1L))).toInt() // samples per duration frame
        val lead = if (keepStart) 0 else (maxOf(0L, dur.first() - 1) * spf).toInt()
        val trail = (maxOf(0L, dur.last() - 1) * spf).toInt()
        val w = if (lead + trail + spf < wav.size) wav.copyOfRange(lead, wav.size - trail) else wav
        for (i in w.indices) w[i] = w[i].coerceIn(-1f, 1f)
        val f = minOf(120, w.size / 8) // ~5 ms fades
        for (i in 0 until f) {
            val g = i.toFloat() / maxOf(1, f - 1)
            w[i] *= g
            w[w.size - 1 - i] *= g
        }
        return w
    }

    /** One inference. ONNX sessions run one call at a time. */
    @Synchronized
    private fun run(lang: String, tok: LongArray, style: FloatArray, speed: Float): Pair<FloatArray, LongArray> {
        val s = sessions[lang] ?: error("Model not loaded for $lang")
        OnnxTensor.createTensor(env, LongBuffer.wrap(tok), longArrayOf(1, tok.size.toLong())).use { tIds ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong())).use { tStyle ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { tSpeed ->
                    s.run(mapOf("input_ids" to tIds, "style" to tStyle, "speed" to tSpeed)).use { result ->
                        val fb = (result[0] as OnnxTensor).floatBuffer
                        val lb = (result[1] as OnnxTensor).longBuffer
                        return FloatArray(fb.remaining()).also { fb.get(it) } to LongArray(lb.remaining()).also { lb.get(it) }
                    }
                }
            }
        }
    }

    /** A voices .npz (a zip of float32 .npy arrays) -> name -> pack. */
    private fun readNpz(file: File): Map<String, Pack> {
        val out = HashMap<String, Pack>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith(".npy")) out[entry.name.removeSuffix(".npy")] = parseNpy(zip.readBytes())
            }
        }
        return out
    }

    private fun parseNpy(bytes: ByteArray): Pack {
        require(bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") { "Not an .npy file" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[6].toInt()
        val headerLen = if (major == 1) bb.getShort(8).toInt() and 0xffff else bb.getInt(8)
        val start = if (major == 1) 10 else 12
        val header = String(bytes, start, headerLen, Charsets.US_ASCII)
        require("'<f4'" in header) { "Voice pack is not float32: $header" }
        val shape = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)!!.groupValues[1]
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
        val n = shape.fold(1) { a, b -> a * b }
        val fb = ByteBuffer.wrap(bytes, start + headerLen, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val data = FloatArray(n).also { fb.get(it) }
        return Pack(data, shape[0], if (shape.size > 1) shape[1] else n)
    }
}
