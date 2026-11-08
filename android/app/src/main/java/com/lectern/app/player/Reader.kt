package com.lectern.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.provider.OpenableColumns
import com.lectern.app.data.LibraryEntry
import com.lectern.app.data.Store
import com.lectern.app.text.Book
import com.lectern.app.text.Parsers
import com.lectern.app.text.Sentence
import com.lectern.app.tts.EnglishPhonemizer
import com.lectern.app.tts.KokoroEngine
import com.lectern.app.tts.ModelFiles
import com.lectern.app.tts.SpanishPhonemizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The whole reading engine: the open book, the sentence list, the position, and the
 * generate-ahead audio pipeline (Kokoro -> AudioTrack). One instance for the app; the UI
 * and the PlaybackService (notification / lock screen) both observe [state].
 */
object Reader {

    enum class Status { IDLE, LOADING, PLAYING, PAUSED, DONE }

    enum class Highlight(val label: String) { ACENTO("Acento"), LIMA("Lima"), CIAN("Cian"), AMBAR("Ámbar") }
    enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }
    /** SYSTEM: the wallpaper color (Android 12+). LIME: the design's acid lime. */
    enum class AccentMode(val label: String) { SYSTEM("Sistema"), LIME("Lima") }

    /** SYSTEM: the phone's own voice, instant. KOKORO: better voice, generated on the phone (slow on older phones). */
    enum class Engine { SYSTEM, KOKORO }

    data class State(
        val book: Book? = null,
        val bookId: String? = null,
        val sentences: List<Sentence> = emptyList(),
        val spanish: Boolean = true,
        val pos: Int = 0,
        val status: Status = Status.IDLE,
        val voice: String = "ef_dora",
        val speed: Float = 1f,
        val accent: SpanishPhonemizer.Accent = SpanishPhonemizer.Accent.LATAM,
        val engine: Engine = Engine.SYSTEM,
        /** Name of the chosen system voice, or null for the default one. */
        val systemVoice: String? = null,
        val systemVoices: List<Pair<String, String>> = emptyList(),
        /** Reading language for the system voice; null = detected from the book (Spanish or English). */
        val language: String? = null,
        /** Languages the phone's engine has, as (code, name). */
        val languages: List<Pair<String, String>> = emptyList(),
        /** Voice pitch for the system engine, 0.5 (low) to 2 (high). */
        val pitch: Float = 1f,
        val textSize: Int = 17,
        val highlight: Highlight = Highlight.LIMA,
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val accentMode: AccentMode = AccentMode.SYSTEM,
        /** Total seconds listened, across all books. */
        val listened: Long = 0,
        val message: String? = null,
        val progress: Float? = null,
        val error: String? = null,
        val busy: String? = null,
        val sleep: Sleep = Sleep.Off,
        val library: List<LibraryEntry> = emptyList(),
    ) {
        val current: Sentence? get() = sentences.getOrNull(pos)
        /** Language code the system voice reads in. */
        val speechLang: String get() = language ?: if (spanish) "es" else "en"
    }

    sealed interface Sleep {
        data object Off : Sleep
        data class At(val endMillis: Long) : Sleep
        data class EndOfSection(val section: Int) : Sleep
    }

    val VOICES: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "Español" to listOf("ef_dora" to "Dora", "em_alex" to "Alex", "em_santa" to "Santa"),
        "English (US), female" to listOf("af_heart" to "Heart", "af_bella" to "Bella", "af_nicole" to "Nicole", "af_sarah" to "Sarah", "af_sky" to "Sky", "af_nova" to "Nova", "af_aoede" to "Aoede", "af_kore" to "Kore", "af_alloy" to "Alloy", "af_jessica" to "Jessica", "af_river" to "River"),
        "English (US), male" to listOf("am_adam" to "Adam", "am_michael" to "Michael", "am_fenrir" to "Fenrir", "am_puck" to "Puck", "am_echo" to "Echo", "am_eric" to "Eric", "am_liam" to "Liam", "am_onyx" to "Onyx"),
        "English (UK), female" to listOf("bf_emma" to "Emma", "bf_isabella" to "Isabella", "bf_alice" to "Alice", "bf_lily" to "Lily"),
        "English (UK), male" to listOf("bm_george" to "George", "bm_lewis" to "Lewis", "bm_daniel" to "Daniel", "bm_fable" to "Fable"),
    )
    val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun voiceName(id: String) = VOICES.flatMap { it.second }.firstOrNull { it.first == id }?.second ?: id
    fun isSpanishVoice(id: String) = id.startsWith("e")
    private val DEFAULT_VOICE = mapOf(true to "ef_dora", false to "af_heart")

    /** How far ahead of the listener audio is generated and kept on disk. */
    private const val PREFETCH_SECONDS = 600.0
    private const val GAP_SENTENCE = 0.06
    private const val GAP_PARAGRAPH = 0.45
    private const val SR = KokoroEngine.SAMPLE_RATE

    private lateinit var app: Context
    private lateinit var store: Store
    private lateinit var files: ModelFiles
    private val engine by lazy { KokoroEngine(app) }
    private val english by lazy { EnglishPhonemizer(app) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speech by lazy { SystemSpeech(app).also { it.listener = speechListener } }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        store = Store(app)
        files = ModelFiles(app)
        _state.update {
            it.copy(
                voice = store.getString("voice", "ef_dora"),
                speed = store.getFloat("speed", 1f).let { s -> SPEEDS.minBy { kotlin.math.abs(it - s) } },
                pitch = store.getFloat("pitch", 1f),
                textSize = store.getFloat("textSize", 17f).toInt(),
                highlight = runCatching { Highlight.valueOf(store.getString("highlight", "LIMA")) }.getOrDefault(Highlight.LIMA),
                language = store.getString("language", "").ifEmpty { null },
                theme = runCatching { ThemeMode.valueOf(store.getString("theme", "SYSTEM")) }.getOrDefault(ThemeMode.SYSTEM),
                accentMode = runCatching { AccentMode.valueOf(store.getString("accentMode", "SYSTEM")) }.getOrDefault(AccentMode.SYSTEM),
                listened = store.getString("listened", "0").toLongOrNull() ?: 0,
                engine = runCatching { Engine.valueOf(store.getString("engine", "SYSTEM")) }.getOrDefault(Engine.SYSTEM),
                systemVoice = store.getString("systemVoice", "").ifEmpty { null },
                accent = runCatching { SpanishPhonemizer.Accent.valueOf(store.getString("accent", "LATAM")) }.getOrDefault(SpanishPhonemizer.Accent.LATAM),
                library = store.library(),
            )
        }
        startListenClock()
    }

    private fun set(f: (State) -> State) = _state.update(f)

    /** Counts listening time once a second while playing; saved every 30 s. */
    private fun startListenClock() {
        scope.launch {
            var unsaved = 0
            while (true) {
                delay(1000)
                if (state.value.status != Status.PLAYING) continue
                set { it.copy(listened = it.listened + 1) }
                if (++unsaved >= 30) { unsaved = 0; store.putString("listened", state.value.listened.toString()) }
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /* Books                                                             */
    /* ---------------------------------------------------------------- */

    fun openUri(uri: Uri) {
        scope.launch {
            set { it.copy(busy = "Abriendo el archivo…", error = null) }
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "document.txt"
                    name to (app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the file"))
                }
                val id = sha1("$name:${bytes.size}:${sha1Bytes(bytes)}")
                val book = withContext(Dispatchers.IO) {
                    Parsers.parse(app, name, bytes) { p, n -> set { it.copy(busy = "Leyendo página $p de $n…") } }
                }
                store.saveBook(id, book)
                setBook(id, book, uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set { it.copy(error = e.message ?: "No se pudo abrir el archivo.") }
            } finally {
                set { it.copy(busy = null) }
            }
        }
    }

    fun openSample(spanish: Boolean) {
        val book = Parsers.parseText(if (spanish) SAMPLE_ES else SAMPLE_EN).let {
            it.copy(title = if (spanish) "Don Quijote (muestra)" else "Pride and Prejudice (sample)")
        }
        setBook(if (spanish) "sample-es" else "sample-en", book, null)
    }

    fun openFromLibrary(entry: LibraryEntry) {
        val cached = store.loadBook(entry.id)
        when {
            cached != null -> setBook(entry.id, cached, entry.uri)
            entry.uri != null -> openUri(Uri.parse(entry.uri))
            else -> set { it.copy(error = "Este libro ya no está disponible.") }
        }
    }

    fun removeFromLibrary(entry: LibraryEntry) {
        if (state.value.bookId == entry.id) closeBook()
        store.remove(entry.id)
        set { it.copy(library = store.library()) }
    }

    fun closeBook() {
        stop()
        stopPrefetch()
        set { it.copy(book = null, bookId = null, sentences = emptyList(), pos = 0, status = Status.IDLE) }
    }

    private fun setBook(id: String, book: Book, uri: String?) {
        stop()
        stopPrefetch()
        val sample = book.sections.flatMap { it.paras }.joinToString(" ").take(20_000)
        val spanish = SpanishPhonemizer.looksSpanish(sample)
        val sentences = Parsers.splitIntoSentences(book, if (spanish) Locale("es") else Locale.ENGLISH)
        val pos = store.position(id).coerceIn(0, maxOf(0, sentences.size - 1))
        // A Spanish book read by an English voice (or the reverse) is unintelligible.
        var voice = state.value.voice
        if (isSpanishVoice(voice) != spanish) voice = store.getString("voice:${if (spanish) "es" else "en"}", DEFAULT_VOICE.getValue(spanish))
        store.putString("voice", voice)
        store.upsert(LibraryEntry(id, book.title, uri, sentences.size, pos, System.currentTimeMillis()))
        set {
            it.copy(
                book = book, bookId = id, sentences = sentences, spanish = spanish, pos = pos,
                status = Status.IDLE, voice = voice, error = null, message = null, library = store.library(), systemVoices = emptyList(),
            )
        }
        if (state.value.engine == Engine.SYSTEM) loadSystemVoices()
    }

    /* ---------------------------------------------------------------- */
    /* Controls                                                          */
    /* ---------------------------------------------------------------- */

    fun togglePlay() = when (state.value.status) {
        Status.PLAYING -> pause()
        Status.PAUSED -> resume()
        Status.LOADING -> if (track != null || speaking) pause() else stop()
        Status.DONE -> playAt(0)
        Status.IDLE -> playAt(state.value.pos)
    }

    fun play() = if (state.value.status == Status.PAUSED) resume() else if (state.value.status != Status.PLAYING) togglePlay() else Unit

    fun playAt(index: Int) {
        val s = state.value
        if (s.sentences.isEmpty()) return
        val target = index.coerceIn(0, s.sentences.size - 1)
        set { it.copy(pos = target, error = null) }
        startPipeline(target)
    }

    fun select(index: Int) {
        val s = state.value
        if (s.status == Status.PLAYING || s.status == Status.PAUSED || s.status == Status.LOADING) playAt(index)
        else {
            val target = index.coerceIn(0, maxOf(0, s.sentences.size - 1))
            set { it.copy(pos = target, status = if (it.status == Status.DONE) Status.IDLE else it.status) }
            s.bookId?.let { store.savePosition(it, target, s.sentences.size) }
        }
    }

    fun next() = select(state.value.pos + 1)
    fun previous() = select(state.value.pos - 1)

    fun jumpToSection(section: Int) {
        val i = state.value.sentences.indexOfFirst { it.section == section }
        if (i >= 0) select(i)
    }

    fun pause() {
        if (speaking) {
            epoch++
            speech.stop()
            set { it.copy(status = Status.PAUSED, message = null) }
            return
        }
        val t = track ?: return
        runCatching { t.pause() }
        set { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        if (speaking) { playAt(state.value.pos); return }
        val t = track
        if (t == null) { playAt(state.value.pos); return }
        requestFocus()
        runCatching { t.play() }
        set { it.copy(status = Status.PLAYING) }
    }

    fun stop() {
        epoch++
        if (speaking) { speaking = false; speech.stop() }
        pipeline?.cancel()
        pipeline = null
        releaseTrack()
        abandonFocus()
        set { it.copy(status = if (it.status == Status.DONE) Status.DONE else Status.IDLE, message = null, progress = null, sleep = Sleep.Off) }
    }

    fun setVoice(id: String) {
        store.putString("voice", id)
        store.putString("voice:${if (isSpanishVoice(id)) "es" else "en"}", id)
        set { it.copy(voice = id) }
        restartIfActive()
    }

    fun setSpeed(speed: Float) {
        store.putFloat("speed", speed)
        set { it.copy(speed = speed) }
        restartIfActive()
    }

    fun setEngine(engine: Engine) {
        store.putString("engine", engine.name)
        set { it.copy(engine = engine) }
        if (engine == Engine.SYSTEM) { stopPrefetch(); loadSystemVoices() }
        restartIfActive()
    }

    /** null = automatic (the book's language). Other languages use the system voice. */
    fun setLanguage(code: String?) {
        store.putString("language", code ?: "")
        set { it.copy(language = code, systemVoices = emptyList()) }
        if (code != null && code != "es" && code != "en" && state.value.engine == Engine.KOKORO) setEngine(Engine.SYSTEM)
        loadSystemVoices()
        restartIfActive()
    }

    fun setSystemVoice(name: String) {
        store.putString("systemVoice", name)
        set { it.copy(systemVoice = name) }
        restartIfActive()
    }

    /** Fills [State.systemVoices] for the open book's language. */
    fun loadSystemVoices() {
        scope.launch {
            if (!speech.init()) return@launch
            val all = speech.voices(state.value.speechLang)
            set { it.copy(systemVoices = all.map { v -> v.name to speech.label(v, all) }, languages = speech.languages()) }
        }
    }

    fun setPitch(pitch: Float) {
        store.putFloat("pitch", pitch)
        set { it.copy(pitch = pitch) }
        if (state.value.engine == Engine.SYSTEM) restartIfActive()
    }

    fun setTextSize(size: Int) {
        store.putFloat("textSize", size.toFloat())
        set { it.copy(textSize = size) }
    }

    fun setTheme(t: ThemeMode) {
        store.putString("theme", t.name)
        set { it.copy(theme = t) }
    }

    fun setAccentMode(a: AccentMode) {
        store.putString("accentMode", a.name)
        set { it.copy(accentMode = a) }
    }

    fun setHighlight(h: Highlight) {
        store.putString("highlight", h.name)
        set { it.copy(highlight = h) }
    }

    fun nextSection() {
        val sec = state.value.current?.section ?: return
        val i = state.value.sentences.indexOfFirst { it.section > sec }
        if (i >= 0) select(i)
    }

    /** Start of this chapter, or of the previous one when already near its start. */
    fun previousSection() {
        val s = state.value
        val sec = s.current?.section ?: return
        val start = s.sentences.indexOfFirst { it.section == sec }
        if (s.pos - start > 2 || sec == 0) select(start)
        else s.sentences.indexOfFirst { it.section == sec - 1 }.takeIf { it >= 0 }?.let { select(it) }
    }

    /** Bytes of generated Kokoro audio on disk. */
    fun audioCacheBytes(): Long = java.io.File(app.cacheDir, "audio").walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clearAudioCache() {
        stopPrefetch()
        if (state.value.engine == Engine.KOKORO && state.value.status != Status.IDLE) stop()
        java.io.File(app.cacheDir, "audio").deleteRecursively()
        cache.reset()
    }

    fun setAccent(accent: SpanishPhonemizer.Accent) {
        store.putString("accent", accent.name)
        set { it.copy(accent = accent) }
        if (isSpanishVoice(state.value.voice)) restartIfActive()
    }

    fun setSleep(minutes: Int?, endOfSection: Boolean = false) {
        val s = state.value
        val sleep = when {
            endOfSection -> Sleep.EndOfSection(s.current?.section ?: 0)
            minutes == null -> Sleep.Off
            else -> Sleep.At(System.currentTimeMillis() + minutes * 60_000L)
        }
        set { it.copy(sleep = sleep) }
    }

    fun dismissError() = set { it.copy(error = null) }

    private fun restartIfActive() {
        val s = state.value
        if (s.status == Status.PLAYING || s.status == Status.LOADING) playAt(s.pos)
        else if (s.status == Status.PAUSED) { stop(); set { it.copy(status = Status.IDLE) } }
    }

    /* ---------------------------------------------------------------- */
    /* System voice                                                      */
    /* ---------------------------------------------------------------- */

    @Volatile private var speaking = false
    /** Index of the next sentence to hand to the system engine. */
    @Volatile private var queuedUpTo = 0
    private const val SPEECH_QUEUE = 6

    private fun startSystem(start: Int) {
        epoch++
        val my = epoch
        pipeline?.cancel()
        releaseTrack()
        speech.stop()
        set { it.copy(status = Status.LOADING, message = null, progress = null) }
        PlaybackService.start(app)
        pipeline = scope.launch {
            if (!speech.init()) {
                if (my == epoch) set {
                    it.copy(
                        status = Status.IDLE,
                        error = "Este teléfono no tiene un motor de voz. Instala \"Servicios de voz de Google\" desde Play Store, o elige Kokoro.",
                    )
                }
                return@launch
            }
            if (my != epoch) return@launch
            val s = state.value
            if (s.systemVoices.isEmpty()) {
                val all = speech.voices(s.speechLang)
                set { it.copy(systemVoices = all.map { v -> v.name to speech.label(v, all) }, languages = speech.languages()) }
            }
            speech.configure(s.speechLang, s.systemVoice, s.speed, s.pitch)
            requestFocus()
            speaking = true
            queuedUpTo = start
            queueMore(my, start)
        }
    }

    /** Keeps a few sentences queued ahead of the one being spoken. */
    @Synchronized
    private fun queueMore(my: Int, current: Int) {
        if (my != epoch) return
        val sentences = state.value.sentences
        val max = speech.maxInput()
        while (queuedUpTo < sentences.size && queuedUpTo < current + SPEECH_QUEUE) {
            val sen = sentences[queuedUpTo]
            val gap = if (sen.last) (GAP_PARAGRAPH * 1000).toLong() else 0L
            speech.speak(my, queuedUpTo, sen.text.take(max), gap)
            queuedUpTo++
        }
    }

    private val speechListener = object : SystemSpeech.Listener {
        override fun onSentenceStart(run: Int, index: Int) {
            if (run != epoch) return
            val s = state.value
            set { it.copy(pos = index, status = if (it.status == Status.LOADING) Status.PLAYING else it.status) }
            s.bookId?.let { store.savePosition(it, index, s.sentences.size) }
            queueMore(run, index)
            checkSleep(index)
        }

        override fun onSentenceDone(run: Int, index: Int) {
            if (run != epoch) return
            val s = state.value
            if (index >= s.sentences.size - 1) {
                speaking = false
                abandonFocus()
                set { it.copy(status = Status.DONE, sleep = Sleep.Off, message = null) }
                s.bookId?.let { store.savePosition(it, s.sentences.size - 1, s.sentences.size) }
                set { it.copy(library = store.library()) }
            }
        }

        override fun onError(run: Int, index: Int) {
            if (run != epoch) return
            // Skip the sentence the engine could not say.
            queueMore(run, index + 1)
        }
    }

    /* ---------------------------------------------------------------- */
    /* Pipeline                                                          */
    /* ---------------------------------------------------------------- */

    @Volatile private var epoch = 0
    private var pipeline: Job? = null
    @Volatile private var track: AudioTrack? = null
    private var voiceData: FloatArray? = null
    /** Seconds of audio generated per second of work, smoothed. Below 1 the phone is slower than reading. */
    @Volatile private var genRate = 0.0

    private val cache by lazy { AudioCache(java.io.File(app.cacheDir, "audio")) }
    private var prefetch: Job? = null
    private var prefetchKey: String? = null
    /** The sentence being played; the prefetcher generates forward from here. */
    private val cursor = java.util.concurrent.atomic.AtomicInteger(0)

    private fun gapSeconds(sentences: List<Sentence>, i: Int) =
        if (sentences.getOrNull(i)?.last == true) GAP_PARAGRAPH else GAP_SENTENCE

    /**
     * How much audio to have ready before playing (and after running out). A phone that
     * generates faster than reading only needs the next sentence. A slower one waits for a
     * bigger cushion, so playback stops a few times for a while instead of stuttering
     * between every sentence; the total waiting time is the same either way.
     */
    private fun bufferTarget(): Double = when {
        genRate == 0.0 -> 0.0
        genRate >= 1.05 -> 0.0
        else -> minOf(120.0, 20.0 + 100.0 * (1.0 - genRate))
    }

    private fun startPipeline(start: Int) {
        if (state.value.engine == Engine.SYSTEM) { startSystem(start); return }
        if (speaking) { speaking = false; speech.stop() }
        epoch++
        val my = epoch
        pipeline?.cancel()
        releaseTrack()
        // Set before starting the service: it stops itself when it sees IDLE.
        set { it.copy(status = Status.LOADING) }
        PlaybackService.start(app)
        pipeline = scope.launch {
            try {
                ensureReady()
                if (my != epoch) return@launch
                cursor.set(start)
                startPrefetch()
                requestFocus()
                val t = createTrack()
                track = t
                runPipeline(my, t, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (my == epoch) {
                    releaseTrack()
                    set { it.copy(status = Status.IDLE, error = e.message ?: "No se pudo generar la voz.", message = null, progress = null) }
                }
            }
        }
    }

    private suspend fun ensureReady() = withContext(Dispatchers.IO) {
        if (!files.hasModel) {
            set { it.copy(message = "Descargando el modelo de voz…", progress = 0f) }
            files.ensureModel { loaded, total ->
                val f = if (total > 0) loaded.toFloat() / total else 0f
                set {
                    it.copy(
                        progress = f,
                        message = "Descargando el modelo de voz: ${(f * 100).toInt()}% de ${maxOf(1, total / 1_000_000)} MB. Solo pasa una vez.",
                    )
                }
            }
        }
        if (!engine.isLoaded) {
            set { it.copy(message = "Cargando el modelo de voz…", progress = null) }
            engine.load(files.model)
        }
        val voice = state.value.voice
        voiceData = engine.loadVoice(voice, files.ensureVoice(voice))
        if (!isSpanishVoice(voice)) {
            set { it.copy(message = "Cargando el diccionario inglés…") }
            english.phonemize("ready")
        }
    }

    /**
     * Generates audio ahead of the cursor into [cache], up to PREFETCH_SECONDS ahead. It keeps
     * running while playback is paused, so a pause also builds up a cushion.
     */
    private fun startPrefetch() {
        val s = state.value
        val bookId = s.bookId ?: return
        SpanishPhonemizer.accent = s.accent
        val key = cache.keyFor(bookId, if (isSpanishVoice(s.voice)) "${s.voice}_${s.accent.name}" else s.voice, s.speed)
        if (key == prefetchKey && prefetch?.isActive == true) return
        prefetch?.cancel()
        prefetchKey = key
        cache.open(key)
        val sentences = s.sentences
        val voice = s.voice
        val speed = s.speed
        val voiceVec = voiceData ?: return
        prefetch = scope.launch(Dispatchers.Default) {
            var failures = 0
            while (isActive) {
                val from = cursor.get()
                val ahead = cache.secondsAhead(from, SR) { gapSeconds(sentences, it) }
                val i = if (ahead > PREFETCH_SECONDS) -1 else cache.firstMissing(from, sentences.size)
                if (i < 0) { delay(500); continue }
                val t0 = System.nanoTime()
                val pcm = try {
                    val text = sentences[i].text
                    val ph = if (isSpanishVoice(voice)) SpanishPhonemizer.phonemize(text) else english.phonemize(text)
                    engine.generate(ph, voiceVec, speed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.w("Lectern", "generate failed at $i", e)
                    if (++failures >= 3) { delay(2000); failures = 0 }
                    FloatArray(0) // stored empty, so playback skips it instead of waiting forever
                }
                if (isActive) cache.write(key, i, pcm)
                val took = (System.nanoTime() - t0) / 1e9
                if (pcm.size > SR / 2) {
                    val instant = (pcm.size.toDouble() / SR) / maxOf(took, 0.001)
                    genRate = if (genRate > 0) genRate * 0.8 + instant * 0.2 else instant
                }
            }
        }
    }

    private fun stopPrefetch() {
        prefetch?.cancel()
        prefetch = null
        prefetchKey = null
    }

    private fun headFrames(t: AudioTrack): Long = (t.playbackHeadPosition.toLong() and 0xffffffffL)

    private suspend fun runPipeline(my: Int, t: AudioTrack, start: Int) = coroutineScope {
        val sentences = state.value.sentences
        val marks = CopyOnWriteArrayList<LongArray>() // [startFrame, sentenceIndex]
        val written = AtomicLong(0)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        // Writer: takes sentences from the cache in order and feeds the AudioTrack.
        launch(Dispatchers.IO) {
            val slice = 4800
            val silence = FloatArray(slice)
            var i = start
            var buffering = true
            var started = false
            while (i < sentences.size) {
                ensureActive()
                if (buffering || !cache.has(i)) {
                    // Not ready: let what is queued finish, stop the track, and wait for a cushion.
                    if (started && !buffering) {
                        while (isActive && headFrames(t) < written.get() - SR / 20) delay(100)
                        runCatching { t.pause() }
                    }
                    buffering = true
                    val target = bufferTarget()
                    while (isActive) {
                        val ready = cache.secondsAhead(i, SR) { gapSeconds(sentences, it) }
                        val allReady = cache.firstMissing(i, sentences.size) < 0
                        if (cache.has(i) && (ready >= target || allReady)) break
                        val pct = if (target > 0) (ready / target).coerceIn(0.0, 1.0).toFloat() else null
                        val msg = if (target > 0) "Preparando audio: ${ready.toInt()} de ${target.toInt()} s. Este teléfono genera la voz más lento de lo que la lee." else "Preparando audio…"
                        if (state.value.status != Status.PAUSED) set { it.copy(status = Status.LOADING, message = msg, progress = pct) }
                        delay(250)
                    }
                    ensureActive()
                    buffering = false
                    started = true
                    if (state.value.status != Status.PAUSED) {
                        runCatching { t.play() }
                        set { it.copy(status = Status.PLAYING, message = null, progress = null) }
                    }
                }
                val pcm = cache.read(i)
                if (pcm == null) { buffering = true; continue }
                marks += longArrayOf(written.get(), i.toLong())
                var off = 0
                while (off < pcm.size) {
                    ensureActive()
                    val r = t.write(pcm, off, minOf(slice, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                    if (r < 0) return@launch
                    off += r
                    written.addAndGet(r.toLong())
                }
                var gap = (gapSeconds(sentences, i) * SR).toInt()
                while (gap > 0) {
                    ensureActive()
                    val r = t.write(silence, 0, minOf(slice, gap), AudioTrack.WRITE_BLOCKING)
                    if (r < 0) return@launch
                    gap -= r
                    written.addAndGet(r.toLong())
                }
                i++
            }
            // Everything is queued; wait for the playhead to reach the end.
            while (isActive && headFrames(t) < written.get() - SR / 20) delay(150)
            done.set(true)
        }

        // Ticker: follows the playhead to highlight the sentence being heard.
        var lastSaved = -1
        while (isActive && my == epoch && !done.get()) {
            val head = headFrames(t)
            val mark = marks.lastOrNull { it[0] <= head }
            if (mark != null) {
                val idx = mark[1].toInt()
                cursor.set(idx)
                val s = state.value
                if (idx != s.pos) set { it.copy(pos = idx) }
                if (idx != lastSaved) {
                    lastSaved = idx
                    s.bookId?.let { store.savePosition(it, idx, sentences.size) }
                }
                checkSleep(idx)
            }
            delay(150)
        }
        if (done.get() && my == epoch) {
            releaseTrack()
            abandonFocus()
            set { it.copy(status = Status.DONE, sleep = Sleep.Off, message = null) }
            state.value.bookId?.let { store.savePosition(it, sentences.size - 1, sentences.size) }
            set { it.copy(library = store.library()) }
        }
    }

    private fun checkSleep(idx: Int) {
        val s = state.value
        if (s.status != Status.PLAYING) return
        val due = when (val sl = s.sleep) {
            Sleep.Off -> false
            is Sleep.At -> System.currentTimeMillis() >= sl.endMillis
            is Sleep.EndOfSection -> (s.sentences.getOrNull(idx)?.section ?: sl.section) != sl.section
        }
        if (due) {
            pause()
            set { it.copy(sleep = Sleep.Off) }
        }
    }

    private fun createTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, SR * 4 / 4)) // about 250 ms, so the highlight stays in sync
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    /* ---------------------------------------------------------------- */
    /* Audio focus                                                       */
    /* ---------------------------------------------------------------- */

    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            if (state.value.status == Status.PLAYING) {
                                pausedByFocus = change != AudioManager.AUDIOFOCUS_LOSS
                                pause()
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus && state.value.status == Status.PAUSED) {
                            pausedByFocus = false
                            resume()
                        }
                    }
                }
                .build()
        }
        am.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    /* ---------------------------------------------------------------- */

    private fun sha1(s: String) = sha1Bytes(s.toByteArray())
    private fun sha1Bytes(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    // Public domain (Miguel de Cervantes, 1605)
    private const val SAMPLE_ES = """Capítulo I

En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor.

Una olla de algo más vaca que carnero, salpicón las más noches, duelos y quebrantos los sábados, lantejas los viernes, algún palomino de añadidura los domingos, consumían las tres partes de su hacienda.

Frisaba la edad de nuestro hidalgo con los cincuenta años; era de complexión recia, seco de carnes, enjuto de rostro, gran madrugador y amigo de la caza."""

    // Public domain (Jane Austen, 1813)
    private const val SAMPLE_EN = """Chapter 1

It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.

However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.

"My dear Mr. Bennet," said his lady to him one day, "have you heard that Netherfield Park is let at last?"

Mr. Bennet replied that he had not."""
}
