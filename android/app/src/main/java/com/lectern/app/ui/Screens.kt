package com.lectern.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lectern.app.data.LibraryEntry
import com.lectern.app.player.Reader
import com.lectern.app.text.Parsers
import com.lectern.app.tts.SpanishPhonemizer
import kotlinx.coroutines.delay
import kotlin.random.Random

private val MIME_TYPES = arrayOf(
    "application/pdf",
    "application/epub+zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/*",
    "application/xhtml+xml",
    "application/octet-stream",
)

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("Biblioteca", Icons.Book),
    PLAYER("Reproductor", Icons.Headphones),
    READER("Lectura", Icons.Screen),
    SETTINGS("Ajustes", Icons.Sliders),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LecternApp() {
    val state by Reader.state.collectAsState()
    val p = LocalPalette.current
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var showVoices by remember { mutableStateOf(false) }
    var showSections by remember { mutableStateOf(false) }

    // Opening a book (picker, sample, "Open with") goes straight to the text.
    var lastBook by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.bookId) {
        if (state.bookId != null && state.bookId != lastBook) tab = Tab.READER
        lastBook = state.bookId
    }
    BackHandler(enabled = tab != Tab.LIBRARY) { tab = Tab.LIBRARY }

    Column(Modifier.fillMaxSize().background(p.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            when (tab) {
                Tab.LIBRARY -> LibraryScreen(state, onOpenReader = { tab = Tab.READER })
                Tab.PLAYER -> if (state.book != null) PlayerScreen(state, onVoices = { showVoices = true }, onReader = { tab = Tab.READER }, onSections = { showSections = true }) else NoBook { tab = Tab.LIBRARY }
                Tab.READER -> if (state.book != null) ReaderScreen(state, onSections = { showSections = true }) else NoBook { tab = Tab.LIBRARY }
                Tab.SETTINGS -> SettingsScreen(state, onVoices = { showVoices = true })
            }
            state.busy?.let { BusyOverlay(it) }
        }
        BottomNav(tab) { tab = it }
    }

    if (showVoices) VoiceSheet(state) { showVoices = false }
    if (showSections) SectionSheet(state) { showSections = false }
}

/* ------------------------------------------------------------------ */
/* Building blocks                                                     */
/* ------------------------------------------------------------------ */

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.void).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.accent))
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { t ->
                val active = t == current
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp)
                        .then(if (active) Modifier.pixelBox(p.high, p.accent, shadow = 2.dp) else Modifier)
                        .clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, tint = if (active) p.accent else p.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(t.label.uppercase(), style = pixelUi(8, if (active) p.accent else p.muted, bold = active), maxLines = 1)
                }
            }
        }
    }
}

/** Top bar: status line plus a badge and a pixel title. */
@Composable
private fun Header(title: String, state: Reader.State) {
    val p = LocalPalette.current
    val (statusText, statusColor) = when (state.status) {
        Reader.Status.PLAYING -> "LEYENDO" to p.accent
        Reader.Status.LOADING -> "PREPARANDO" to p.cyan
        Reader.Status.PAUSED -> "EN PAUSA" to p.muted
        Reader.Status.DONE -> "TERMINADO" to p.cyanSoft
        Reader.Status.IDLE -> "LISTO" to p.muted
    }
    Column(Modifier.fillMaxWidth().background(p.void).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor))
            Spacer(Modifier.width(8.dp))
            Text("LECTERN", style = pixelUi(9, p.accent))
            Text("  ::  ", style = telemetry(10, p.outline))
            Text(if (state.engine == Reader.Engine.SYSTEM) "VOZ TELÉFONO" else "KOKORO", style = telemetry(10, p.muted))
            Spacer(Modifier.weight(1f))
            Text(statusText, style = pixelUi(8, statusColor), modifier = Modifier.border(1.dp, statusColor.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).pixelBox(p.accent, Color.Black, shadow = 2.dp), contentAlignment = Alignment.Center) {
                Text("LT", style = pixelTitle(8, p.onAccent))
            }
            Spacer(Modifier.width(10.dp))
            Text(title.uppercase(), style = pixelTitle(14, p.strong))
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(p.accent.copy(alpha = 0.4f)))
}

@Composable
private fun SectionLabel(text: String, color: Color? = null, trailing: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color ?: p.accent))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = pixelUi(9, color ?: p.accent, bold = true), modifier = Modifier.weight(1f))
        trailing?.let { Text(it.uppercase(), style = pixelUi(8, p.outline)) }
    }
}

/** Discrete pixel progress: [■■■■□□□□] */
@Composable
private fun BlockBar(fraction: Float, blocks: Int, modifier: Modifier = Modifier, color: Color? = null, height: Dp = 8.dp) {
    val p = LocalPalette.current
    val filled = (fraction.coerceIn(0f, 1f) * blocks).let { if (fraction > 0f && it < 1f) 1 else it.toInt() }
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(blocks) { i ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < filled) color ?: p.accent else p.highest))
        }
    }
}

@Composable
private fun PixelButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val fill = if (primary) color ?: p.accent else p.high
    val ink = if (primary) p.onAccent else color ?: p.ink
    Row(
        modifier
            .pixelBox(fill, if (primary) Color.Black else p.edge, shadow = 3.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = ink, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text.uppercase(), style = pixelUi(10, ink, bold = true), maxLines = 1)
    }
}

@Composable
private fun SquareButton(icon: ImageVector, label: String?, desc: String, size: Dp = 52.dp, primary: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .size(size)
            .pixelBox(if (primary) p.accent else p.low, if (primary) Color.Black else p.edge, shadow = 3.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, desc, tint = if (primary) p.onAccent else p.ink, modifier = Modifier.size(if (primary) size * 0.45f else 18.dp))
        label?.let { Text(it, style = telemetry(8, p.muted, 700), modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .pixelBox(if (active) p.accent else p.container, if (active) Color.Black else p.seam, shadow = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = pixelUi(9, if (active) p.onAccent else p.muted, bold = active), maxLines = 1)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, border: Color? = null, glow: Color? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().pixelBox(p.container, border ?: p.seam, shadow = 3.dp, glow = glow).padding(12.dp)) { content() }
}

@Composable
private fun BusyOverlay(text: String) {
    val p = LocalPalette.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(140); tick++ } }
    Box(Modifier.fillMaxSize().background(p.void.copy(alpha = 0.9f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            BlockBar(((tick % 17) / 16f), 16, Modifier.width(200.dp), height = 10.dp)
            Spacer(Modifier.height(14.dp))
            Text(text, style = telemetry(12, p.ink), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ErrorBox(text: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().pixelBox(p.container, p.alert, shadow = 3.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ERROR", style = pixelUi(9, p.alert, bold = true))
        Spacer(Modifier.width(10.dp))
        Text(text, style = grotesk(13, p.ink), modifier = Modifier.weight(1f))
        Icon(Icons.Close, "Cerrar", tint = p.muted, modifier = Modifier.size(28.dp).clickable { Reader.dismissError() }.padding(6.dp))
    }
}

@Composable
private fun NoBook(onLibrary: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SIN LIBRO", style = pixelTitle(14, p.accent))
        Spacer(Modifier.height(12.dp))
        Text("Abre un archivo o una muestra desde la biblioteca.", style = grotesk(15, p.muted), textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PixelButton("Ir a la biblioteca", primary = true, icon = Icons.Book, onClick = onLibrary)
    }
}

/* ------------------------------------------------------------------ */
/* Shared reading math                                                 */
/* ------------------------------------------------------------------ */

/** Estimated seconds before each sentence (at speed 1), from word counts. */
private fun cumulativeSeconds(state: Reader.State): DoubleArray {
    val out = DoubleArray(state.sentences.size + 1)
    state.sentences.forEachIndexed { i, s -> out[i + 1] = out[i] + s.text.split(' ').size / Parsers.WORDS_PER_MINUTE.toDouble() * 60 }
    return out
}

private fun clock(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun languageLabel(state: Reader.State): String {
    val name = state.languages.firstOrNull { it.first == state.speechLang }?.second
        ?: java.util.Locale(state.speechLang).getDisplayLanguage(java.util.Locale("es")).replaceFirstChar { it.uppercase() }
    return if (state.language == null) "$name (automático)" else name
}

private fun speedLabel(s: Float) = "${s.toString().removeSuffix(".0")}x"

private fun voiceLabel(state: Reader.State) = if (state.engine == Reader.Engine.SYSTEM)
    state.systemVoices.firstOrNull { it.first == state.systemVoice }?.second ?: state.systemVoices.firstOrNull()?.second ?: "Voz del teléfono"
else "Kokoro · ${Reader.voiceName(state.voice)}"

/* ------------------------------------------------------------------ */
/* Library                                                             */
/* ------------------------------------------------------------------ */

private enum class Filter(val label: String) { ALL("Todos"), PROGRESS("En progreso"), DONE("Completados") }

@Composable
private fun LibraryScreen(state: Reader.State, onOpenReader: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Reader.openUri(uri)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    fun progressOf(e: LibraryEntry) = if (e.total > 1) e.pos.toFloat() / (e.total - 1) else 0f
    val done = state.library.count { progressOf(it) >= 0.999f }
    val shown = state.library
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .filter {
            when (filter) {
                Filter.ALL -> true
                Filter.PROGRESS -> progressOf(it) < 0.999f
                Filter.DONE -> progressOf(it) >= 0.999f
            }
        }

    Column(Modifier.fillMaxSize()) {
        Header("Biblioteca", state)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { item { ErrorBox(it) } }

            // Open a file
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pixelBox(p.accent, p.accent, shadow = 3.dp)
                        .clickable { picker.launch(MIME_TYPES) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).pixelBox(p.well, p.accent, shadow = 2.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Upload, null, tint = p.accent, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ABRIR ARCHIVO", style = pixelTitle(11, p.onAccent))
                        Spacer(Modifier.height(4.dp))
                        Text("PDF · EPUB · DOCX · TXT · HTML", style = pixelUi(9, p.onAccent.copy(alpha = 0.8f), bold = true))
                    }
                    Text("+ ADD", style = pixelUi(9, p.accent), modifier = Modifier.background(p.well).padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelButton("Muestra español", Modifier.weight(1f)) { Reader.openSample(true) }
                    PixelButton("English sample", Modifier.weight(1f)) { Reader.openSample(false) }
                }
            }

            // Now playing
            state.book?.let { book ->
                item {
                    val total = state.sentences.size
                    val frac = if (total > 1) state.pos.toFloat() / (total - 1) else 0f
                    val cum = remember(state.sentences) { cumulativeSeconds(state) }
                    val left = (cum.last() - cum[state.pos.coerceIn(0, total)]) / state.speed
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(if (state.status == Reader.Status.PLAYING) "Reproduciendo" else "Continuar", trailing = "[${speedLabel(state.speed)}]")
                        Panel(border = p.accent, glow = p.accent.copy(alpha = 0.3f)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(64.dp, 80.dp).pixelBox(p.well, p.accent.copy(alpha = 0.6f), shadow = 2.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Doc, null, tint = p.accent, modifier = Modifier.size(28.dp))
                                    Text("${(frac * 100).toInt()}%", style = pixelUi(8, p.accent), modifier = Modifier.align(Alignment.BottomEnd).background(p.well).padding(2.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(book.title, style = grotesk(16, p.strong, 700), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(state.current?.let { book.sections.getOrNull(it.section)?.title } ?: "", style = grotesk(13, p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(6.dp))
                                    Text("FRASE ${state.pos + 1}/$total  ·  ${clock(left)} REST.", style = pixelUi(8, p.cyanSoft))
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            BlockBar(frac, 20, height = 10.dp)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixelButton("Texto", icon = Icons.Screen, onClick = onOpenReader)
                                Spacer(Modifier.weight(1f))
                                val playing = state.status == Reader.Status.PLAYING || state.status == Reader.Status.LOADING
                                PixelButton(if (playing) "Pausar" else "Reanudar", primary = true, icon = if (playing) Icons.Pause else Icons.Play) { Reader.togglePlay() }
                            }
                        }
                    }
                }
            }

            // Search and filters
            if (state.library.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().pixelBox(p.low, p.seam, shadow = 3.dp).padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("BUSCAR>", style = pixelUi(9, p.accent))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Filtrar por título…", style = telemetry(12, p.outline))
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = telemetry(12, p.ink), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                        }
                        Icon(Icons.Search, null, tint = p.accent, modifier = Modifier.size(16.dp))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            val n = when (f) { Filter.ALL -> state.library.size; Filter.PROGRESS -> state.library.size - done; Filter.DONE -> done }
                            Chip("${f.label} ($n)", f == filter, Modifier.weight(1f)) { filter = f }
                        }
                    }
                }
                item { SectionLabel("Tus archivos", trailing = "Orden: reciente") }
                items(shown, key = { it.id }) { entry -> LibraryCard(entry, current = entry.id == state.bookId) }
                if (shown.isEmpty()) item { Text("Nada coincide con el filtro.", style = grotesk(14, p.muted)) }
            } else {
                item {
                    Panel {
                        Text("TU BIBLIOTECA ESTÁ VACÍA", style = pixelUi(10, p.accent, bold = true))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Abre un archivo o prueba una muestra. Los PDF escaneados (fotos de páginas) y los libros con DRM no se pueden leer. Tus archivos no salen del teléfono.",
                            style = grotesk(14, p.muted),
                        )
                    }
                }
            }

            // Stats
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Tiempo escuchado", "${state.listened / 3600}h ${(state.listened % 3600) / 60}m", "${state.library.size} archivos", p.accent, Modifier.weight(1f))
                    StatTile("Velocidad", speedLabel(state.speed), voiceLabel(state), p.cyan, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, accent: Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.pixelBox(p.low, p.seam, shadow = 3.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent)); Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), style = pixelUi(8, p.muted))
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = pixelTitle(14, p.strong))
        Spacer(Modifier.height(6.dp))
        Text(sub.uppercase(), style = pixelUi(8, accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LibraryCard(entry: LibraryEntry, current: Boolean) {
    val p = LocalPalette.current
    val progress = if (entry.total > 1) entry.pos.toFloat() / (entry.total - 1) else 0f
    val finished = progress >= 0.999f
    val accent = if (finished) p.strong else if (current) p.accent else p.cyan
    Column(Modifier.fillMaxWidth().pixelBox(p.container, if (current) p.accent else p.seam, shadow = 3.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp, 52.dp).pixelBox(p.well, accent.copy(alpha = 0.6f), shadow = 2.dp), contentAlignment = Alignment.Center) {
                Icon(if (finished) Icons.Check else Icons.Doc, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { Reader.openFromLibrary(entry) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (finished) Text("COMPLETADO", style = pixelUi(8, p.well, bold = true), modifier = Modifier.background(p.strong).padding(horizontal = 4.dp, vertical = 1.dp))
                    else {
                        Box(Modifier.size(6.dp).background(accent)); Spacer(Modifier.width(5.dp))
                        Text(if (current) "ABIERTO" else "EN PROGRESO", style = pixelUi(8, accent, bold = true))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(entry.title, style = grotesk(15, p.strong, 700), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${entry.total} frases", style = grotesk(12, p.muted))
            }
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SquareButton(Icons.Play, null, "Abrir", size = 36.dp) { Reader.openFromLibrary(entry) }
                SquareButton(Icons.Close, null, "Quitar de la biblioteca", size = 36.dp) { Reader.removeFromLibrary(entry) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().background(p.well.copy(alpha = 0.5f)).border(1.dp, p.seam).padding(8.dp)) {
            Row {
                Text("AVANCE: ${(progress * 100).toInt()}%", style = pixelUi(8, p.strong), modifier = Modifier.weight(1f))
                Text(if (finished) "FINALIZADO ■" else "FRASE ${entry.pos + 1}/${entry.total}", style = pixelUi(8, accent, bold = true))
            }
            Spacer(Modifier.height(6.dp))
            BlockBar(progress, 16, color = if (finished) p.accent else accent, height = 6.dp)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Player                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun PlayerScreen(state: Reader.State, onVoices: () -> Unit, onReader: () -> Unit, onSections: () -> Unit) {
    val p = LocalPalette.current
    val book = state.book ?: return
    val total = state.sentences.size
    val section = state.current?.section ?: 0
    val cum = remember(state.sentences) { cumulativeSeconds(state) }
    val elapsed = cum[state.pos.coerceIn(0, total)] / state.speed
    val length = cum.last() / state.speed
    val playing = state.status == Reader.Status.PLAYING || state.status == Reader.Status.LOADING
    var sleepMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header("Reproductor", state)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { ErrorBox(it) }

            // File banner
            Row(Modifier.fillMaxWidth().pixelBox(p.low, p.high, shadow = 3.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Doc, null, tint = p.accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp))
                        Text(book.title, style = telemetry(12, p.accent, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Capítulo ${section + 1} de ${book.sections.size}  ::  Frase ${state.pos + 1} de $total", style = telemetry(12, p.muted))
                }
                Spacer(Modifier.width(8.dp))
                SquareButton(Icons.List, null, "Capítulos", size = 40.dp, onClick = onSections)
            }

            // Deck with the pixel equalizer
            Column(Modifier.fillMaxWidth().pixelBox(p.container, p.edge, borderWidth = 3.dp, shadow = 4.dp).padding(12.dp)) {
                Row {
                    Text(if (state.engine == Reader.Engine.SYSTEM) "MOTOR :: TELÉFONO" else "MOTOR :: KOKORO", style = telemetry(10, p.accent, 700), modifier = Modifier.weight(1f))
                    Text(if (state.spanish) "IDIOMA // ES" else "IDIOMA // EN", style = telemetry(10, p.cyanSoft, 700))
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().background(p.void).border(2.dp, p.edge).padding(vertical = 18.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Equalizer(active = state.status == Reader.Status.PLAYING)
                    Spacer(Modifier.height(14.dp))
                    Text(book.sections.getOrNull(section)?.title ?: book.title, style = grotesk(20, p.ink, 700), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.title, style = grotesk(14, p.muted), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("VOZ: ${voiceLabel(state).uppercase()}", style = telemetry(10, p.muted), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${((if (total > 1) state.pos.toFloat() / (total - 1) else 0f) * 100).toInt()}%", style = telemetry(10, p.accent, 700))
                }
            }

            // Times and seek bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeBox(clock(elapsed), p.accent)
                    Text("RESTANTE: -${clock(length - elapsed)}", style = telemetry(11, p.muted), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    TimeBox(clock(length), p.ink)
                }
                SeekBar(if (total > 1) state.pos.toFloat() / (total - 1) else 0f) { f -> Reader.select((f * (total - 1)).toInt()) }
                // Chapter segments: played cyan, current lime, rest dim
                val segs = book.sections.size.coerceAtMost(24)
                if (segs > 1) Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(segs) { i ->
                        val sec = i * book.sections.size / segs
                        val c = when { sec < section -> p.cyanSoft; sec == section -> p.accent; else -> p.high }
                        Box(Modifier.weight(1f).fillMaxHeight().background(c))
                    }
                }
            }

            // Controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SquareButton(Icons.PrevChapter, "CAP -1", "Capítulo anterior") { Reader.previousSection() }
                SquareButton(Icons.Prev, "FRASE", "Frase anterior") { Reader.previous() }
                SquareButton(if (playing) Icons.Pause else Icons.Play, null, if (playing) "Pausa" else "Reproducir", size = 68.dp, primary = true) { Reader.togglePlay() }
                SquareButton(Icons.Next, "FRASE", "Frase siguiente") { Reader.next() }
                SquareButton(Icons.NextChapter, "CAP +1", "Capítulo siguiente") { Reader.nextSection() }
            }
            state.message?.let { msg ->
                Column {
                    Text(msg, style = telemetry(11, p.cyanSoft))
                    state.progress?.let { Spacer(Modifier.height(6.dp)); BlockBar(it, 20, color = p.cyan, height = 6.dp) }
                }
            }

            // Speed
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VELOCIDAD DE LECTURA", style = telemetry(10, p.muted, 700), modifier = Modifier.weight(1f))
                    Text("[ ${speedLabel(state.speed)} ACTIVO ]", style = telemetry(10, p.accent, 700))
                }
                SpeedGrid(state.speed)
            }

            // Voice
            Row(Modifier.fillMaxWidth().pixelBox(p.container, p.seam, shadow = 3.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).pixelBox(p.high, p.edge, shadow = 2.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Voice, null, tint = p.accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("VOZ", style = telemetry(10, p.muted, 700))
                    Text(voiceLabel(state), style = grotesk(14, p.ink, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PixelButton("Cambiar", icon = Icons.Sliders, color = p.accent, onClick = onVoices)
            }

            // Sleep timer + synced text
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    val sleepText = when (val s = state.sleep) {
                        Reader.Sleep.Off -> "Apagado"
                        is Reader.Sleep.At -> "En ${maxOf(1, ((s.endMillis - System.currentTimeMillis()) / 60_000).toInt())} min"
                        is Reader.Sleep.EndOfSection -> "Fin del capítulo"
                    }
                    ActionCard(Icons.Moon, p.cyanSoft, "Temporizador", sleepText, badge = if (state.sleep != Reader.Sleep.Off) "ON" else null) { sleepMenu = true }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }, containerColor = p.container) {
                        listOf<Pair<String, () -> Unit>>(
                            "Apagado" to { Reader.setSleep(null) },
                            "15 minutos" to { Reader.setSleep(15) },
                            "30 minutos" to { Reader.setSleep(30) },
                            "60 minutos" to { Reader.setSleep(60) },
                            "Al terminar el capítulo" to { Reader.setSleep(null, endOfSection = true) },
                        ).forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label, style = grotesk(14, p.ink)) }, onClick = { action(); sleepMenu = false })
                        }
                    }
                }
                ActionCard(Icons.Screen, p.accent, "Modo vista", "Texto sincronizado", Modifier.weight(1f), onClick = onReader)
            }

            // Live transcript
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TRANSCRIPCIÓN EN VIVO", style = telemetry(10, p.muted, 700), modifier = Modifier.weight(1f))
                    Text("FRASE ${state.pos + 1}", style = telemetry(10, p.accent, 700), modifier = Modifier.border(1.dp, p.edge).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(8.dp))
                val hl = p.highlight(state.highlight)
                val text = buildAnnotatedString {
                    state.sentences.getOrNull(state.pos - 1)?.let { withStyle(SpanStyle(color = p.muted.copy(alpha = 0.6f))) { append(it.text.takeLast(140)); append(" ") } }
                    state.current?.let { withStyle(SpanStyle(background = hl, color = p.onHighlight(state.highlight), fontWeight = FontWeight.SemiBold)) { append(it.text) } }
                    state.sentences.getOrNull(state.pos + 1)?.let { withStyle(SpanStyle(color = p.muted)) { append(" "); append(it.text.take(140)) } }
                }
                Text(text, style = grotesk(15, p.ink, line = 1.6f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TimeBox(text: String, color: Color) {
    val p = LocalPalette.current
    Text(text, style = telemetry(13, color, 700), modifier = Modifier.pixelBox(p.void, p.edge, borderWidth = 1.dp, shadow = 1.dp).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pixelBox(p.void, p.edge, shadow = 0.dp)
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } }
            .padding(3.dp),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).background(p.accent))
    }
}

@Composable
private fun SpeedGrid(current: Float) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().pixelBox(p.void, p.edge, shadow = 0.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Reader.SPEEDS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s -> Chip(speedLabel(s), s == current, Modifier.weight(1f)) { Reader.setSpeed(s) } }
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, tint: Color, label: String, value: String, modifier: Modifier = Modifier, badge: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().pixelBox(p.container, p.seam, shadow = 3.dp).clickable(onClick = onClick).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            badge?.let { Text(it, style = telemetry(10, p.accent, 700), modifier = Modifier.border(1.dp, p.accent.copy(alpha = 0.5f)).padding(horizontal = 5.dp, vertical = 1.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label.uppercase(), style = telemetry(10, p.muted, 700))
        Text(value, style = grotesk(15, p.ink, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Decorative pixel bars that move while audio plays. */
@Composable
private fun Equalizer(active: Boolean) {
    val p = LocalPalette.current
    val heights = remember { IntArray(12) { 2 + it % 5 } }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (active) {
            delay(200)
            for (i in heights.indices) heights[i] = Random.nextInt(1, 10)
            frame++
        }
    }
    @Suppress("UNUSED_EXPRESSION") frame
    Row(Modifier.height(72.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEachIndexed { i, h ->
            Column(Modifier.width(9.dp).alpha(if (active) 1f else 0.3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(h) { Box(Modifier.fillMaxWidth().height(6.dp).background(if (i % 3 == 2) p.cyanSoft else p.accent)) }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Reader (synced text)                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun ReaderScreen(state: Reader.State, onSections: () -> Unit) {
    val p = LocalPalette.current
    val book = state.book ?: return
    val sentences = state.sentences
    val total = sentences.size
    val currentSection = state.current?.section ?: 0
    val cum = remember(sentences) { cumulativeSeconds(state) }
    val playing = state.status == Reader.Status.PLAYING || state.status == Reader.Status.LOADING
    val hl = p.highlight(state.highlight)

    val paragraphs = remember(sentences, currentSection) {
        val out = ArrayList<IntArray>()
        var lastPara = -1
        val buf = ArrayList<Int>()
        sentences.forEachIndexed { i, s ->
            if (s.section != currentSection) return@forEachIndexed
            if (s.para != lastPara && buf.isNotEmpty()) { out += buf.toIntArray(); buf.clear() }
            lastPara = s.para
            buf += i
        }
        if (buf.isNotEmpty()) out += buf.toIntArray()
        out
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.pos, paragraphs) {
        val item = paragraphs.indexOfFirst { state.pos in it }
        if (item < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        val target = item + 1
        if (visible.isEmpty() || target < visible.first() || target >= visible.last()) listState.animateScrollToItem(target, scrollOffset = -200)
    }

    Column(Modifier.fillMaxSize()) {
        Header("Lectura", state)
        // Document strip
        Column(Modifier.fillMaxWidth().background(p.low).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DOC", style = pixelUi(9, p.accent), modifier = Modifier.background(p.well).border(1.dp, p.accent.copy(alpha = 0.4f)).padding(horizontal = 5.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(book.title.uppercase(), style = telemetry(11, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[FRASE] ${state.pos + 1} / $total", style = telemetry(11, p.ink), modifier = Modifier.weight(1f))
                val frac = if (total > 1) state.pos.toFloat() / (total - 1) else 0f
                Text("%.1f%% COMPLETADO".format(frac * 100), style = telemetry(11, p.accent, 700), modifier = Modifier.background(p.well).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            BlockBar(if (total > 1) state.pos.toFloat() / (total - 1) else 0f, 24, Modifier.background(p.well).border(2.dp, p.highest).padding(3.dp), height = 10.dp)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.well))
        state.error?.let { Box(Modifier.padding(12.dp)) { ErrorBox(it) } }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().pixelBox(p.container, Color.Black, shadow = 3.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("CAP.${currentSection + 1}", style = pixelUi(9, p.accent, bold = true), modifier = Modifier.background(p.well).border(1.dp, p.accent.copy(alpha = 0.3f)).padding(horizontal = 6.dp, vertical = 4.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(book.sections.getOrNull(currentSection)?.title ?: "", style = telemetry(11, p.muted), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        PixelButton("Índice", icon = Icons.List, onClick = onSections)
                    }
                }
                itemsIndexed(paragraphs) { _, indices ->
                    val text = buildAnnotatedString {
                        indices.forEach { i ->
                            withLink(LinkAnnotation.Clickable("s$i") { Reader.select(i) }) {
                                withStyle(
                                    when {
                                        i == state.pos -> SpanStyle(background = hl, color = p.onHighlight(state.highlight), fontWeight = FontWeight.SemiBold)
                                        i < state.pos -> SpanStyle(color = p.muted.copy(alpha = 0.5f))
                                        else -> SpanStyle(color = p.ink)
                                    },
                                ) { append(sentences[i].text) }
                            }
                            append(" ")
                        }
                    }
                    Text(text, style = grotesk(state.textSize, p.ink, line = 1.75f))
                }
                item {
                    Text("[--- FIN DEL CAPÍTULO ${currentSection + 1} ---]", style = pixelUi(8, p.accent.copy(alpha = 0.7f)), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            // Floating control deck
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .pixelBox(p.well, p.accent, shadow = 5.dp)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(clock(cum[state.pos.coerceIn(0, total)] / state.speed), style = pixelUi(11, p.accent, bold = true))
                        Text(" / ${clock(cum.last() / state.speed)}", style = pixelUi(9, p.muted))
                    }
                    Text(state.message ?: "Voz: ${voiceLabel(state)}", style = telemetry(10, if (state.message != null) p.cyanSoft else p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                DeckButton("Tt") {
                    val sizes = listOf(15, 17, 19, 22)
                    Reader.setTextSize(sizes[(sizes.indexOf(state.textSize) + 1).mod(sizes.size)])
                }
                Spacer(Modifier.width(6.dp))
                DeckButton(speedLabel(state.speed)) {
                    val i = Reader.SPEEDS.indexOf(state.speed)
                    Reader.setSpeed(Reader.SPEEDS[(i + 1).mod(Reader.SPEEDS.size)])
                }
                Spacer(Modifier.width(6.dp))
                PixelButton(if (playing) "Pausa" else "Leer", primary = true, icon = if (playing) Icons.Pause else Icons.Play, color = hl) { Reader.togglePlay() }
            }
        }
    }
}

@Composable
private fun DeckButton(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.height(40.dp).pixelBox(p.container, Color.Black, shadow = 2.dp).clickable(onClick = onClick).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, style = pixelUi(10, p.ink, bold = true))
    }
}

/* ------------------------------------------------------------------ */
/* Settings                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun SettingsScreen(state: Reader.State, onVoices: () -> Unit) {
    val p = LocalPalette.current
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheTick) {
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Reader.audioCacheBytes() }
    }
    LaunchedEffect(Unit) { if (state.systemVoices.isEmpty()) Reader.loadSystemVoices() }

    Column(Modifier.fillMaxSize()) {
        Header("Ajustes", state)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Voice engine
            SettingsSection("Voz", "Motor") {
                EngineOption(
                    "Voz del teléfono", "Lee al instante, sin descargas. Usa las voces instaladas en Android.",
                    "RÁPIDA", p.accent, state.engine == Reader.Engine.SYSTEM,
                ) { Reader.setEngine(Reader.Engine.SYSTEM) }
                EngineOption(
                    "Kokoro", "Voz más natural, generada en el teléfono. Descarga 92 MB; en teléfonos lentos prepara el audio antes y hace pausas.",
                    "NATURAL", p.cyan, state.engine == Reader.Engine.KOKORO,
                ) { Reader.setEngine(Reader.Engine.KOKORO) }
                Row(Modifier.fillMaxWidth().pixelBox(p.low, p.seam, shadow = 2.dp).clickable(onClick = onVoices).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VOZ E IDIOMA", style = telemetry(10, p.muted, 700))
                        Text(voiceLabel(state), style = grotesk(15, p.ink, 600))
                        if (state.engine == Reader.Engine.SYSTEM) Text(languageLabel(state), style = grotesk(13, p.muted))
                    }
                    Text("CAMBIAR ▶", style = pixelUi(9, p.accent, bold = true))
                }
                if (state.engine == Reader.Engine.KOKORO) {
                    Text("ACENTO (ESPAÑOL)", style = telemetry(10, p.muted, 700))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpanishPhonemizer.Accent.entries.forEach { a -> Chip(a.label, a == state.accent, Modifier.weight(1f)) { Reader.setAccent(a) } }
                    }
                }
                StepSetting("Velocidad", speedLabel(state.speed), Reader.SPEEDS.map { speedLabel(it) }, Reader.SPEEDS.indexOf(state.speed)) { Reader.setSpeed(Reader.SPEEDS[it]) }
                if (state.engine == Reader.Engine.SYSTEM) {
                    val pitches = listOf(0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
                    val labels = listOf("-3", "-2", "-1", "0", "+1", "+2", "+3")
                    val i = pitches.indexOfFirst { kotlin.math.abs(it - state.pitch) < 0.01f }.coerceAtLeast(3)
                    StepSetting("Tono", if (i == 3) "0 (neutro)" else labels[i], labels, i, accent = p.cyan) { Reader.setPitch(pitches[it]) }
                }
            }

            // Appearance
            SettingsSection("Apariencia", "Tema") {
                Text("TEMA", style = telemetry(10, p.muted, 700))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reader.ThemeMode.entries.forEach { t -> Chip(t.label, t == state.theme, Modifier.weight(1f)) { Reader.setTheme(t) } }
                }
                Text(
                    when (state.theme) {
                        Reader.ThemeMode.SYSTEM -> "Claro u oscuro según tu teléfono."
                        Reader.ThemeMode.LIGHT -> "Siempre claro."
                        Reader.ThemeMode.DARK -> "Siempre oscuro."
                    },
                    style = grotesk(13, p.muted),
                )
                Text("COLOR DE ACENTO", style = telemetry(10, p.muted, 700))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reader.AccentMode.entries.forEach { a -> Chip(a.label, a == state.accentMode, Modifier.weight(1f)) { Reader.setAccentMode(a) } }
                }
                Text(
                    if (android.os.Build.VERSION.SDK_INT >= 31) "\"Sistema\" usa el color de tu fondo de pantalla."
                    else "El color del sistema necesita Android 12 o superior; se usa lima.",
                    style = grotesk(13, p.muted),
                )
            }

            // Reading
            SettingsSection("Lectura", "Vista") {
                val sizes = listOf(14, 15, 16, 17, 19, 21, 24)
                StepSetting("Tamaño de letra", "${state.textSize} px", sizes.map { "$it" }, sizes.indexOf(state.textSize).let { if (it < 0) 3 else it }) { Reader.setTextSize(sizes[it]) }
                Text("COLOR DEL RESALTADO", style = telemetry(10, p.muted, 700))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Reader.Highlight.entries.forEach { h ->
                        val active = h == state.highlight
                        Row(
                            Modifier.weight(1f).pixelBox(if (active) p.high else p.low, if (active) p.highlight(h) else p.seam, shadow = 2.dp).clickable { Reader.setHighlight(h) }.padding(10.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).background(p.highlight(h)).border(1.dp, Color.Black))
                            Spacer(Modifier.width(6.dp))
                            Text(h.label.uppercase(), style = pixelUi(9, if (active) p.highlight(h) else p.muted, bold = true))
                        }
                    }
                }
                val hl = p.highlight(state.highlight)
                Text(
                    buildAnnotatedString {
                        append("Así se ve la frase que se está leyendo: ")
                        withStyle(SpanStyle(background = hl, color = p.onHighlight(state.highlight), fontWeight = FontWeight.SemiBold)) { append("en un lugar de la Mancha.") }
                    },
                    style = grotesk(state.textSize, p.ink, line = 1.7f),
                )
            }

            // Storage
            SettingsSection("Almacenamiento", "Caché") {
                val mb = if (cacheBytes < 0) "…" else "%.1f MB".format(cacheBytes / 1_048_576.0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUDIO DE KOKORO GUARDADO", style = telemetry(10, p.muted, 700))
                        Text("Se reutiliza al volver a escuchar. Máximo 400 MB.", style = grotesk(13, p.muted))
                    }
                    Text(mb, style = telemetry(12, p.accent, 700), modifier = Modifier.background(p.well).border(1.dp, p.accent.copy(alpha = 0.4f)).padding(horizontal = 6.dp, vertical = 3.dp))
                }
                BlockBar((cacheBytes.coerceAtLeast(0) / (400.0 * 1_048_576)).toFloat(), 24, color = p.cyan, height = 10.dp)
                PixelButton("Borrar audio guardado", Modifier.fillMaxWidth(), primary = true, color = p.alert) {
                    Reader.clearAudioCache(); cacheTick++
                }
            }

            // About
            SettingsSection("Acerca de", "v0.2") {
                Text(
                    "Lectern lee PDF, EPUB, DOCX, TXT y HTML. Los PDF escaneados (fotos de páginas) y los libros con DRM no se pueden leer. Tus archivos no salen del teléfono.",
                    style = grotesk(14, p.muted),
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, tag: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().pixelBox(p.low, p.high, shadow = 3.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = grotesk(19, p.ink, 600), modifier = Modifier.weight(1f))
            Text(tag.uppercase(), style = telemetry(10, p.muted, 700), modifier = Modifier.background(p.well).border(1.dp, p.highest).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.high))
        content()
    }
}

@Composable
private fun EngineOption(title: String, desc: String, tag: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().pixelBox(if (selected) p.high else p.low, if (selected) p.accent else p.seam, shadow = if (selected) 3.dp else 2.dp)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).background(p.well).border(2.dp, if (selected) p.accent else p.outline).padding(4.dp)) {
            if (selected) Box(Modifier.fillMaxSize().background(p.accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = grotesk(15, p.ink, 600))
            Text(desc, style = telemetry(10, p.muted))
        }
        Spacer(Modifier.width(8.dp))
        Text(tag, style = telemetry(9, accent, 700), modifier = Modifier.background(p.well).border(1.dp, accent.copy(alpha = 0.4f)).padding(horizontal = 5.dp, vertical = 2.dp))
    }
}

/** A row of pixel steps you tap to pick a value. */
@Composable
private fun StepSetting(label: String, value: String, steps: List<String>, selected: Int, accent: Color? = null, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    val c = accent ?: p.accent
    Column(Modifier.fillMaxWidth().pixelBox(p.surface, p.high, shadow = 2.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(c)); Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), style = telemetry(11, p.muted), modifier = Modifier.weight(1f))
            Text(value, style = telemetry(11, c, 700), modifier = Modifier.background(p.well).border(1.dp, c.copy(alpha = 0.4f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Row(Modifier.fillMaxWidth().height(28.dp).background(p.well).border(2.dp, p.highest).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            steps.indices.forEach { i ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (i <= selected) c else p.high).clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == selected) Box(Modifier.size(6.dp).background(p.onAccent))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            steps.forEachIndexed { i, s ->
                Text(s, style = telemetry(9, if (i == selected) c else p.outline), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Sheets                                                              */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(state: Reader.State, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var showLanguages by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.languages.isEmpty() || state.systemVoices.isEmpty()) Reader.loadSystemVoices() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.container, shape = androidx.compose.ui.graphics.RectangleShape) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Teléfono", state.engine == Reader.Engine.SYSTEM, Modifier.weight(1f)) { Reader.setEngine(Reader.Engine.SYSTEM) }
                    Chip("Kokoro", state.engine == Reader.Engine.KOKORO, Modifier.weight(1f)) { Reader.setEngine(Reader.Engine.KOKORO) }
                }
            }
            if (state.engine == Reader.Engine.SYSTEM) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                            .pixelBox(p.low, p.seam, shadow = 2.dp).clickable { showLanguages = !showLanguages }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("IDIOMA DE LECTURA", style = telemetry(10, p.muted, 700))
                            Text(languageLabel(state), style = grotesk(15, p.ink, 600))
                        }
                        Text(if (showLanguages) "CERRAR ▲" else "CAMBIAR ▼", style = pixelUi(9, p.accent, bold = true))
                    }
                }
                if (showLanguages) {
                    item {
                        SheetRow("Automático (${if (state.spanish) "español" else "inglés"}, según el libro)", state.language == null) {
                            Reader.setLanguage(null); showLanguages = false
                        }
                    }
                    if (state.languages.isEmpty()) item { Text("Cargando idiomas…", style = grotesk(14, p.muted), modifier = Modifier.padding(16.dp)) }
                    items(state.languages) { (code, name) ->
                        SheetRow(name, state.language == code) { Reader.setLanguage(code); showLanguages = false }
                    }
                    item {
                        Text(
                            "¿Falta un idioma? Instálalo en Ajustes de Android › Sistema › Idiomas › Salida de texto a voz.",
                            style = grotesk(13, p.muted), modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { SheetLabel("Voces del teléfono") }
                if (state.systemVoices.isEmpty()) item { Text("Cargando voces…", style = grotesk(14, p.muted), modifier = Modifier.padding(16.dp)) }
                items(state.systemVoices) { (name, label) ->
                    val selected = name == state.systemVoice || (state.systemVoice !in state.systemVoices.map { it.first } && name == state.systemVoices.first().first)
                    SheetRow(label, selected) { Reader.setSystemVoice(name); onDismiss() }
                }
            } else {
                Reader.VOICES.forEach { (group, voices) ->
                    item { SheetLabel(group) }
                    items(voices) { (id, name) -> SheetRow(name, id == state.voice) { Reader.setVoice(id); onDismiss() } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionSheet(state: Reader.State, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val book = state.book ?: return
    val currentSection = state.current?.section ?: 0
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.container, shape = androidx.compose.ui.graphics.RectangleShape) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item { SheetLabel("Capítulos") }
            itemsIndexed(book.sections) { i, s ->
                val minutes = Parsers.countWords(s.paras) / Parsers.WORDS_PER_MINUTE.toDouble() * 60
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (i == currentSection) p.high else Color.Transparent)
                        .clickable { Reader.jumpToSection(i); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(i + 1), style = telemetry(11, if (i == currentSection) p.accent else p.outline, 700))
                    Spacer(Modifier.width(12.dp))
                    Text(s.title, style = grotesk(15, if (i == currentSection) p.accent else p.ink, if (i == currentSection) 700 else 400), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Parsers.formatDuration(minutes), style = telemetry(10, p.muted))
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(p.accent)); Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = pixelUi(9, p.accent, bold = true))
    }
}

@Composable
private fun SheetRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.high else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (selected) p.accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Text(text, style = grotesk(15, if (selected) p.accent else p.ink, if (selected) 700 else 400))
    }
}
