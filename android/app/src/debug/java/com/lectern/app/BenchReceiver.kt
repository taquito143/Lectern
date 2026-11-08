package com.lectern.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lectern.app.tts.SpanishPhonemizer
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.concurrent.thread

/** Debug-only speed test of Kokoro on the device. Results go to logcat tag LecternBench. */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val model = intent.getStringExtra("model") ?: "model_quantized.onnx"
        val threads = intent.getIntExtra("threads", 4)
        val xnn = intent.getBooleanExtra("xnnpack", false)
        val spin = intent.getBooleanExtra("spin", true)
        thread(name = "bench") {
            try { run(app, model, threads, xnn, spin) } catch (e: Throwable) { Log.e(TAG, "failed", e) }
        }
    }

    private fun run(context: Context, model: String, threads: Int, xnn: Boolean, spin: Boolean) {
        val dir = File(context.filesDir, "kokoro")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(if (xnn) 1 else threads)
            if (!spin) addConfigEntry("session.intra_op.allow_spinning", "0")
            if (xnn) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }
        val t0 = System.nanoTime()
        val s = env.createSession(File(dir, model).absolutePath, opts)
        Log.i(TAG, "$model threads=$threads xnn=$xnn spin=$spin load=${ms(t0)}ms inputs=${s.inputNames}")
        val vocab = JSONObject(context.assets.open("kokoro_vocab.json").bufferedReader().readText())
        val voiceBytes = File(dir, "voices/ef_dora.bin").readBytes()
        val voice = FloatArray(voiceBytes.size / 4)
        ByteBuffer.wrap(voiceBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(voice)
        val texts = listOf(
            "Hola.",
            "En un lugar de la Mancha, de cuyo nombre no quiero acordarme.",
            "No ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor.",
        )
        val names = s.inputNames
        val idsName = names.first { "id" in it }; val styleName = names.first { "style" in it }; val speedName = names.first { "speed" in it }
        for (round in 0 until 2) for (text in texts) {
            val ph = SpanishPhonemizer.phonemize(text)
            val ids = ArrayList<Long>()
            for (cp in ph.codePoints().toArray()) {
                val ch = String(Character.toChars(cp))
                if (vocab.has(ch)) ids.add(vocab.getLong(ch))
            }
            val input = LongArray(ids.size + 2).also { a -> ids.forEachIndexed { i, v -> a[i + 1] = v } }
            val row = minOf(ids.size, voice.size / 256 - 1)
            val style = voice.copyOfRange(row * 256, row * 256 + 256)
            val t1 = System.nanoTime()
            val samples = OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { a ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)).use { b ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)).use { c ->
                        s.run(mapOf(idsName to a, styleName to b, speedName to c)).use { r -> (r[0] as OnnxTensor).floatBuffer.remaining() }
                    }
                }
            }
            val took = ms(t1)
            val audio = samples / 24.0
            Log.i(TAG, "round=$round tokens=${ids.size} audio=${audio.toInt()}ms gen=${took}ms rtf=${"%.2f".format(audio / took)}x")
        }
        s.close()
        Log.i(TAG, "done")
    }

    private fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000

    companion object { const val TAG = "LecternBench" }
}
