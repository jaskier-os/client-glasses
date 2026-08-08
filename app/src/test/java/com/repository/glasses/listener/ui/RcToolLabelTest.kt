package com.repository.glasses.listener.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tool-run row's label.
 *
 * It used to render as a bare `">> 3"`, which taught nobody what the number counted. It now reads
 * "3 tools called" beside a wrench glyph. The singular is the whole reason this is a function and
 * not a string template: "1 tools called" is the kind of thing that ships and then irritates
 * forever.
 */
class RcToolLabelTest {

    @Test
    fun oneToolIsSingular() {
        assertEquals("1 tool called", RcToolLabel.of(1))
    }

    @Test
    fun severalToolsArePlural() {
        assertEquals("2 tools called", RcToolLabel.of(2))
        assertEquals("3 tools called", RcToolLabel.of(3))
        assertEquals("17 tools called", RcToolLabel.of(17))
    }

    /**
     * Zero is plural in English ("0 tools"), and the row should never be built for it anyway --
     * but a defensive default beats a crash or a "0 tool called".
     */
    @Test
    fun zeroIsPlural() {
        assertEquals("0 tools called", RcToolLabel.of(0))
    }

    /** A negative count is corrupt data, not a reason to render nonsense. */
    @Test
    fun negativeCountsClampToZero() {
        assertEquals("0 tools called", RcToolLabel.of(-4))
    }

    /** The old chevron-and-number form must be gone: it is what the user asked to replace. */
    @Test
    fun theLabelNeverRendersTheOldChevronForm() {
        for (n in 0..5) {
            val label = RcToolLabel.of(n)
            assert(!label.startsWith(">>")) { "still rendering the old chevron form: '$label'" }
            assert(label.contains("called")) { "label must say what happened: '$label'" }
        }
    }
}
