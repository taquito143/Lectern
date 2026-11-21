package com.lectern.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lectern.app.tts.EnglishPhonemizer
import com.lectern.app.tts.KittenEngine
import com.lectern.app.tts.ModelFiles
import com.lectern.app.tts.SpanishPhonemizer
import kotlin.concurrent.thread

/**
 * Debug-only speed test of KittenTTS on the device. Results go to logcat tag LecternBench.
 *   adb shell am broadcast -n com.lectern.app/.BenchReceiver --es lang es
 * Downloads the language's model first if needed.
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val lang = intent.getStringExtra("lang") ?: "es"
        thread(name = "bench") {
            try { run(app, lang) } catch (e: Throwable) { Log.e(TAG, "failed", e) }
        }
    }

    private fun run(context: Context, lang: String) {
        val files = ModelFiles(context)
        files.ensure(lang) { _, _ -> }
        val engine = KittenEngine(context)
        val t0 = System.nanoTime()
        engine.load(lang, files.model(lang), files.voices(lang))
        Log.i(TAG, "lang=$lang load=${ms(t0)}ms")
        val (voice, texts) = if (lang == "es") "sofia" to listOf(
            "Hola.",
            "En un lugar de la Mancha, de cuyo nombre no quiero acordarme.",
            "No ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor.",
        ) else "eleanor" to listOf(
            "Hello.",
            "Have you heard that Netherfield Park is let at last?",
            "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        )
        val english = if (lang == "en") EnglishPhonemizer(context) else null
        val pack = engine.voice(lang, voice)
        for (round in 0 until 2) for (text in texts) {
            val ph = english?.phonemize(text) ?: SpanishPhonemizer.phonemize(text)
            val t1 = System.nanoTime()
            val samples = engine.generate(lang, ph, pack, 1f).size
            val took = ms(t1)
            val audio = samples / 24.0
            Log.i(TAG, "round=$round phonemes=${ph.length} audio=${audio.toInt()}ms gen=${took}ms rtf=${"%.2f".format(audio / maxOf(took, 1))}x  $ph")
        }
        Log.i(TAG, "done")
    }

    private fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000

    companion object { const val TAG = "LecternBench" }
}
