package com.lectern.app.tts

import org.junit.Test
import java.io.File

/** Writes phonemes for each line of $LECTERN_ES_IN to $LECTERN_ES_OUT, to compare with spanish.js. */
class SpanishPhonemizerDump {
    @Test
    fun dump() {
        val input = System.getenv("LECTERN_ES_IN") ?: return
        val out = File(System.getenv("LECTERN_ES_OUT"))
        out.writeText(File(input).readLines(Charsets.UTF_8).joinToString("\n") { SpanishPhonemizer.phonemize(it) }, Charsets.UTF_8)
    }
}
