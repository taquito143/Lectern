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

/**
 * Kokoro-82M on ONNX Runtime. Input: phoneme string. Output: 24 kHz mono float PCM.
 * Mirrors kokoro-js generate_from_ids: ids are padded with 0 on both ends and the
 * voice style vector is picked by the number of phoneme tokens.
 */
class KokoroEngine(context: Context) {

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val STYLE_DIM = 256
        private const val MAX_TOKENS = 510
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var idsName = "input_ids"
    private var styleName = "style"
    private var speedName = "speed"

    private val vocab: Map<Int, Long> = run {
        val json = JSONObject(context.assets.open("kokoro_vocab.json").bufferedReader(Charsets.UTF_8).readText())
        val map = HashMap<Int, Long>()
        for (key in json.keys()) map[key.codePointAt(0)] = json.getLong(key)
        map
    }

    private val voices = HashMap<String, FloatArray>()

    val isLoaded get() = session != null

    fun load(model: File) {
        if (session != null) return
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        }
        val s = env.createSession(model.absolutePath, opts)
        val names = s.inputNames
        names.firstOrNull { "id" in it }?.let { idsName = it }
        names.firstOrNull { "style" in it }?.let { styleName = it }
        names.firstOrNull { "speed" in it }?.let { speedName = it }
        session = s
    }

    fun loadVoice(id: String, file: File): FloatArray = voices.getOrPut(id) {
        val bytes = file.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    }

    fun tokenize(phonemes: String): LongArray {
        val out = ArrayList<Long>(phonemes.length)
        var i = 0
        while (i < phonemes.length && out.size < MAX_TOKENS) {
            val cp = phonemes.codePointAt(i)
            vocab[cp]?.let { out += it }
            i += Character.charCount(cp)
        }
        return out.toLongArray()
    }

    /** Runs the model. Only one inference at a time (ONNX sessions are reused). */
    @Synchronized
    fun generate(phonemes: String, voice: FloatArray, speed: Float): FloatArray {
        val s = session ?: error("Model not loaded")
        val ids = tokenize(phonemes)
        if (ids.isEmpty()) return FloatArray(0)
        val input = LongArray(ids.size + 2)
        ids.copyInto(input, 1)

        val rows = voice.size / STYLE_DIM
        val row = minOf(ids.size, rows - 1)
        val style = voice.copyOfRange(row * STYLE_DIM, row * STYLE_DIM + STYLE_DIM)

        OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { tIds ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong())).use { tStyle ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { tSpeed ->
                    s.run(mapOf(idsName to tIds, styleName to tStyle, speedName to tSpeed)).use { result ->
                        val out = result[0] as OnnxTensor
                        val fb = out.floatBuffer
                        return FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                }
            }
        }
    }
}
