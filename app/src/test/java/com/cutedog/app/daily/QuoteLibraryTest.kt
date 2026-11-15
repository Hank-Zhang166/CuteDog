package com.cutedog.app.daily

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.Normalizer

class QuoteLibraryTest {
    @Test fun shippedLibraryHasOneFullLeapYearOfDistinctReadableQuotes() {
        val asset = sequenceOf(File("src/main/assets/quotes.txt"), File("app/src/main/assets/quotes.txt"))
            .firstOrNull { it.isFile } ?: error("Actual shipped quotes.txt asset is missing")
        val quotes = asset.readLines(Charsets.UTF_8)
        assertTrue("Need at least 366 original lines", quotes.size >= 366)
        assertTrue("Blank lines are not quotes", quotes.none { it.isBlank() })
        val normalized = quotes.map { Normalizer.normalize(it, Normalizer.Form.NFKC).replace(Regex("[\\p{P}\\s]"), "") }
        assertEquals("Duplicate normalized quotes", quotes.size, normalized.distinct().size)
        assertTrue("Quotes must remain legible in a compact bubble", quotes.all { it.length in 8..56 })
        assertTrue("No placeholder text", quotes.none { it.contains("TODO") || it.contains("占位") })
    }
}
