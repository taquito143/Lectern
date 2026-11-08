package com.lectern.app.player

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

/**
 * The phone's own text-to-speech engine (usually Google's). It speaks in real time on any
 * phone, so it is the fast option next to Kokoro. Sentences are queued a few at a time;
 * each utterance id is "<run>:<sentence index>" so callbacks from a stopped run are ignored.
 */
class SystemSpeech(private val context: Context) {

    interface Listener {
        fun onSentenceStart(run: Int, index: Int)
        fun onSentenceDone(run: Int, index: Int)
        fun onError(run: Int, index: Int)
    }

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    var listener: Listener? = null

    suspend fun init(): Boolean {
        ready?.let { return it.await() }
        val d = CompletableDeferred<Boolean>()
        ready = d
        tts = TextToSpeech(context) { status -> d.complete(status == TextToSpeech.SUCCESS) }.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { parse(id)?.let { (r, i) -> listener?.onSentenceStart(r, i) } }
                override fun onDone(id: String) { parse(id)?.let { (r, i) -> listener?.onSentenceDone(r, i) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { parse(id)?.let { (r, i) -> listener?.onError(r, i) } }
                override fun onError(id: String, errorCode: Int) { parse(id)?.let { (r, i) -> listener?.onError(r, i) } }
            })
        }
        val ok = d.await()
        if (!ok) { ready = null; tts = null }
        return ok
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
    }

    /** Installed voices for a language, offline ones first, best quality first. */
    fun voices(lang: String): List<Voice> =
        installed()
            .filter { it.locale.language == lang }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { regionRank(it.locale) }, { it.locale.toString() }, { -it.quality }, { it.name }))

    private fun installed(): List<Voice> = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
        .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }

    /** Every language the phone's engine can speak, as (code, name in Spanish), sorted by name. */
    fun languages(): List<Pair<String, String>> =
        installed().map { it.locale.language }.distinct()
            .map { code -> code to Locale(code).getDisplayLanguage(Locale("es")).replaceFirstChar { it.uppercase() } }
            .sortedBy { it.second }

    /** Latin American Spanish before Spain's; US English first. */
    private fun regionRank(l: Locale): Int {
        val order = if (l.language == "es") listOf("AR", "UY", "US", "MX", "419", "CO", "CL", "PE") else listOf("US", "GB")
        val i = order.indexOf(l.country)
        return when {
            i >= 0 -> i
            l.language == "es" && l.country == "ES" -> 100
            else -> 50
        }
    }

    /** "Español (Argentina) · 2", numbered within each country. */
    fun label(v: Voice, all: List<Voice>): String {
        val same = all.filter { it.locale == v.locale }
        // Google's es-US voices are Latin American Spanish; "Estados Unidos" reads as a mistake.
        val country = if (v.locale.language == "es" && v.locale.country in setOf("US", "419")) "Latinoamérica"
        else v.locale.getDisplayCountry(Locale("es")).ifEmpty { v.locale.getDisplayLanguage(Locale("es")) }
        val base = country.replaceFirstChar { it.uppercase() }
        val n = if (same.size > 1) " · ${same.indexOf(v) + 1}" else ""
        val net = if (v.isNetworkConnectionRequired) " (en línea)" else ""
        return base + n + net
    }

    fun configure(lang: String, voiceName: String?, speed: Float, pitch: Float) {
        val t = tts ?: return
        val all = voices(lang)
        val v = all.firstOrNull { it.name == voiceName } ?: all.firstOrNull()
        if (v != null) t.voice = v else t.language = Locale(lang)
        t.setSpeechRate(speed)
        t.setPitch(pitch)
    }

    fun speak(run: Int, index: Int, text: String, pauseAfterMs: Long) {
        val t = tts ?: return
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "$run:$index")
        if (pauseAfterMs > 0) t.playSilentUtterance(pauseAfterMs, TextToSpeech.QUEUE_ADD, "gap")
    }

    fun stop() { runCatching { tts?.stop() } }

    fun maxInput(): Int = TextToSpeech.getMaxSpeechInputLength()
}
