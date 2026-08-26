package com.github.xepozz.testo.tests.console

// Per-message rendering flags carried on a channel message's `channel` attribute, after a reserved `/`.
// `s`/`b`/`n` are mutually exclusive separation modes (last written wins); `c` gives the message its own card; `t` hides
// it in aggregate views. The bare channel name — everything keyed by channel — never carries these; they ride the chunk.

/** How a message joins the previous message of the same channel run inside a merged card. */
enum class ChannelSeparation { STREAM, DEFAULT, BLOCK, NEW_BLOCK }

data class ChannelFlags(
    val separation: ChannelSeparation = ChannelSeparation.DEFAULT,
    val card: Boolean = false,
    val testOnly: Boolean = false,
)

/** A `channel` attribute split into its bare name and the flags parsed off the suffix. */
data class ParsedChannel(val name: String, val flags: ChannelFlags)

/**
 * Split a raw `channel` attribute into name and flags. The `/` is reserved: the name is everything before the first
 * one, the flag section everything after. That section is scanned letter by letter — known flag letters apply, anything
 * else (an unknown letter, a future non-flag setting) is ignored — so an old plugin skips a flag it doesn't know rather
 * than losing output or inventing a channel.
 */
fun parseChannel(raw: String): ParsedChannel {
    val slash = raw.indexOf('/')
    if (slash < 0) return ParsedChannel(raw, ChannelFlags())
    var separation = ChannelSeparation.DEFAULT
    var card = false
    var testOnly = false
    for (letter in raw.substring(slash + 1)) when (letter) {
        's' -> separation = ChannelSeparation.STREAM
        'b' -> separation = ChannelSeparation.BLOCK
        'n' -> separation = ChannelSeparation.NEW_BLOCK
        'c' -> card = true
        't' -> testOnly = true
    }
    return ParsedChannel(raw.substring(0, slash), ChannelFlags(separation, card, testOnly))
}

// Newlines a mode guarantees on each side of its message; the separator between two messages is the max of the previous
// message's `after` and the current message's `before`, minus the breaks the text on either side already carries.
private fun beforeBreaks(separation: ChannelSeparation): Int = when (separation) {
    ChannelSeparation.STREAM -> 0
    ChannelSeparation.DEFAULT -> 1
    ChannelSeparation.BLOCK -> 1
    ChannelSeparation.NEW_BLOCK -> 2
}

private fun afterBreaks(separation: ChannelSeparation): Int = when (separation) {
    ChannelSeparation.STREAM -> 0
    ChannelSeparation.DEFAULT -> 0
    ChannelSeparation.BLOCK -> 1
    ChannelSeparation.NEW_BLOCK -> 2
}

private fun trailingNewlines(text: CharSequence): Int {
    var count = 0
    var i = text.length - 1
    while (i >= 0 && text[i] == '\n') { count++; i-- }
    return count
}

private fun leadingNewlines(text: CharSequence): Int {
    var count = 0
    while (count < text.length && text[count] == '\n') count++
    return count
}

/**
 * How many newlines to insert between [accumulated] (a card's current text) and the incoming [message], given the
 * separation mode of the previous message and of this one. Breaks already present at the boundary are never duplicated.
 */
fun separatorNewlines(
    previous: ChannelSeparation?,
    current: ChannelSeparation,
    accumulated: CharSequence,
    message: CharSequence,
): Int {
    if (accumulated.isEmpty()) return 0
    val required = maxOf(previous?.let(::afterBreaks) ?: 0, beforeBreaks(current))
    val have = trailingNewlines(accumulated) + leadingNewlines(message)
    return (required - have).coerceAtLeast(0)
}
