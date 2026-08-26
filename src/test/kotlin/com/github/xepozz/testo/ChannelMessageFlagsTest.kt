package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelFlags
import com.github.xepozz.testo.tests.console.ChannelSeparation
import com.github.xepozz.testo.tests.console.parseChannel
import com.github.xepozz.testo.tests.console.separatorNewlines
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JUnit4 tests for the channel-flag parser and the message-joining separator — no IDE platform needed. */
class ChannelMessageFlagsTest {

    @Test
    fun plainNameHasNoFlags() {
        assertEquals("app.log" to ChannelFlags(), parseChannel("app.log").let { it.name to it.flags })
    }

    @Test
    fun cardFlag() {
        val parsed = parseChannel("query.sql/c")
        assertEquals("query.sql", parsed.name)
        assertEquals(ChannelFlags(card = true), parsed.flags)
    }

    @Test
    fun testOnlyCombinesWithSeparation() {
        val parsed = parseChannel("app.log/tn")
        assertEquals("app.log", parsed.name)
        assertEquals(ChannelFlags(separation = ChannelSeparation.NEW_BLOCK, testOnly = true), parsed.flags)
    }

    @Test
    fun mutuallyExclusiveSeparationLastWins() {
        assertEquals(ChannelSeparation.NEW_BLOCK, parseChannel("app.log/sn").flags.separation)
        assertEquals(ChannelSeparation.STREAM, parseChannel("app.log/ns").flags.separation)
    }

    @Test
    fun cardAndSeparationBothRecorded() {
        // Supersession (a card ignores its separation) is a render-time rule; the parse keeps both.
        val flags = parseChannel("query.sql/cs").flags
        assertEquals(true, flags.card)
        assertEquals(ChannelSeparation.STREAM, flags.separation)
    }

    @Test
    fun streamAndTestOnly() {
        assertEquals(
            ChannelFlags(separation = ChannelSeparation.STREAM, testOnly = true),
            parseChannel("trace.log/ts").flags,
        )
    }

    @Test
    fun slashSeparatesNameFromFlagsUnknownLettersIgnored() {
        // The slash is reserved: the name is everything before it. 'b' applies; 'a'/'r' are unknown and ignored.
        val parsed = parseChannel("foo/bar")
        assertEquals("foo", parsed.name)
        assertEquals(ChannelFlags(separation = ChannelSeparation.BLOCK), parsed.flags)
    }

    @Test
    fun knownFlagAppliesAlongsideUnknownLetters() {
        // 'n' applies, 'x' is ignored — the unknown letter does not void the known one.
        assertEquals("app.log", parseChannel("app.log/nx").name)
        assertEquals(ChannelFlags(separation = ChannelSeparation.NEW_BLOCK), parseChannel("app.log/nx").flags)
    }

    @Test
    fun aLoneUnknownLetterLeavesDefaultFlags() {
        assertEquals("app.log", parseChannel("app.log/x").name)
        assertEquals(ChannelFlags(), parseChannel("app.log/x").flags)
    }

    @Test
    fun emptyFlagSectionIsDefault() {
        assertEquals("app.log", parseChannel("app.log/").name)
        assertEquals(ChannelFlags(), parseChannel("app.log/").flags)
    }

    @Test
    fun splitsOnTheFirstSlash() {
        // A later '/' is just another ignored character in the flag section, so the name never carries a slash.
        val parsed = parseChannel("a/b/c")
        assertEquals("a", parsed.name)
        assertEquals(ChannelFlags(separation = ChannelSeparation.BLOCK, card = true), parsed.flags)
    }

    @Test
    fun repeatedLetterReadOnce() {
        assertEquals(ChannelFlags(card = true), parseChannel("q.sql/cc").flags)
    }

    @Test
    fun firstMessageGetsNoSeparator() {
        assertEquals(0, separatorNewlines(null, ChannelSeparation.DEFAULT, "", "hello"))
    }

    @Test
    fun defaultInsertsOneBreakWhenMissing() {
        assertEquals(1, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.DEFAULT, "a", "b"))
    }

    @Test
    fun defaultNeverDuplicatesAnExistingBreak() {
        assertEquals(0, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.DEFAULT, "a\n", "b"))
        assertEquals(0, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.DEFAULT, "a", "\nb"))
    }

    @Test
    fun streamGlues() {
        assertEquals(0, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.STREAM, "a", "b"))
        assertEquals(0, separatorNewlines(ChannelSeparation.STREAM, ChannelSeparation.STREAM, "...", "."))
    }

    @Test
    fun newBlockGuaranteesABlankLine() {
        assertEquals(2, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.NEW_BLOCK, "a", "b"))
        assertEquals(1, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.NEW_BLOCK, "a\n", "b"))
        assertEquals(0, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.NEW_BLOCK, "a\n\n", "b"))
    }

    @Test
    fun blockPadsAgainstEitherNeighbour() {
        // A previous block guarantees a break after it, even before a stream message.
        assertEquals(1, separatorNewlines(ChannelSeparation.BLOCK, ChannelSeparation.STREAM, "a", "b"))
        // A block message guarantees a break before it.
        assertEquals(1, separatorNewlines(ChannelSeparation.DEFAULT, ChannelSeparation.BLOCK, "a", "b"))
    }
}
