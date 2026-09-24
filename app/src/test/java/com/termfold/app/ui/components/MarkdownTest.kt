package com.termfold.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent replies stream in chunks, so the parser has to be right about partial text too. */
class MarkdownTest {

    @Test
    fun `headings paragraphs and fenced code split into blocks`() {
        val blocks = parseMarkdown(
            """
            # Title
            Some **bold** text
            that continues.

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Some **bold** text\nthat continues."), blocks[1])
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[2])
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an unclosed fence mid-stream renders as code rather than prose`() {
        val blocks = parseMarkdown("Here:\n```sh\nnpm install\nnpm te")
        assertEquals(MdBlock.Code("sh", "npm install\nnpm te"), blocks.last())
    }

    @Test
    fun `bullets and numbered lists keep their order and indentation`() {
        val blocks = parseMarkdown("- one\n  - nested\n- two\n\n3. third\n4. fourth")
        val bullets = blocks[0] as MdBlock.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(listOf(ListItem(0, "one"), ListItem(1, "nested"), ListItem(0, "two")), bullets.items)
        val numbered = blocks[1] as MdBlock.ListBlock
        assertTrue(numbered.ordered)
        assertEquals(3, numbered.start)
        assertEquals(2, numbered.items.size)
    }

    @Test
    fun `rules and quotes are recognised`() {
        val blocks = parseMarkdown("> careful\n\n---\n\nafter")
        assertEquals(MdBlock.Quote("careful"), blocks[0])
        assertEquals(MdBlock.Rule, blocks[1])
        assertEquals(MdBlock.Paragraph("after"), blocks[2])
    }
}
