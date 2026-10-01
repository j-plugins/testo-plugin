package com.github.xepozz.testo.infection

import com.intellij.openapi.diagnostic.logger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Infection's HTML report, made fit for opening from disk once it is written. */
internal object TestoInfectionHtmlReport {
    // Infection's template links "Back" to "/": the site root on a server, the drive root over file://.
    private val BACK_LINK = "<a href=\"/\">Back</a>".toByteArray(Charsets.US_ASCII)

    // The link opens the template's <body>; the report JSON, megabytes of it, comes after.
    private const val HEAD_BYTES = 4096

    /** Blanks the link out in place: the rest of the file, the report itself, is not rewritten. */
    fun clean(report: Path) {
        if (!Files.isRegularFile(report)) return
        runCatching {
            FileChannel.open(report, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                val head = ByteBuffer.allocate(minOf(HEAD_BYTES.toLong(), channel.size()).toInt())
                while (head.hasRemaining() && channel.read(head) >= 0) {}
                val at = backLinkOffset(head.array())
                if (at >= 0) channel.write(ByteBuffer.wrap(ByteArray(BACK_LINK.size) { ' '.code.toByte() }), at.toLong())
            }
        }.onFailure { logger<TestoInfectionHtmlReport>().warn("Could not clean the mutation report $report", it) }
    }

    fun backLinkOffset(head: ByteArray): Int {
        outer@ for (i in 0..head.size - BACK_LINK.size) {
            for (j in BACK_LINK.indices) if (head[i + j] != BACK_LINK[j]) continue@outer
            return i
        }
        return -1
    }
}
