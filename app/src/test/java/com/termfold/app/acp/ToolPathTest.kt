package com.termfold.app.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recovering a file name from a replayed tool result (issue #3). */
class ToolPathTest {

    private fun path(text: String) = RESULT_PATH.find(text)?.groupValues?.get(1)?.trimEnd('.', ',', ':', ')', '\'', '"')

    @Test
    fun `pi write and edit results name their file`() {
        assertEquals("/Demo/snake.html", path("Successfully wrote 4213 bytes to /Demo/snake.html"))
        assertEquals("src/app.ts", path("Successfully replaced text in src/app.ts."))
        assertEquals("index.html", path("Successfully replaced 2 occurrences in index.html"))
    }

    @Test
    fun `other agents' phrasing`() {
        assertEquals("docs/notes.md", path("Created docs/notes.md"))
        assertEquals("a.py", path("Updated file: a.py"))
        assertEquals("fib.py", path("I wrote `fib.py` for you"))
    }

    @Test
    fun `ordinary output is not mistaken for a path`() {
        assertNull(path("0 1 1 2 3 5 8 13 21 34"))
        assertNull(path("All tests passed"))
    }
}
