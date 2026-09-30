package com.github.xepozz.testo.infection

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage

/**
 * Reads `infection --teamcity` into a [TestoMutationRun]. Process output arrives in arbitrary chunks, so it is cut
 * into lines here, per stream.
 *
 * A mutant's `testStdOut` carries a name but no `nodeId`, so mutants are looked up by name as well.
 *
 * With [rerun], the stream is one of a rerun: it adds nothing and updates the run's own mutants, found by their ID.
 */
internal class TestoMutationStream(
    private val run: TestoMutationRun,
    /** Every complete line, as it was read: what [TestoMutationArchive] keeps. */
    private val onLine: ((String) -> Unit)? = null,
    private val rerun: Boolean = false,
    /** Each file as it is first announced: a live run fingerprints it there. */
    private val onFile: ((MutatedFile) -> Unit)? = null,
) {
    private val files = HashMap<String, MutatedFile>()
    private val byId = HashMap<String, Mutant>()
    private val byName = HashMap<String, Mutant>()
    private val pending = HashMap<Boolean, StringBuilder>()

    @Synchronized
    fun feed(text: String, stdout: Boolean) {
        val buffer = pending.getOrPut(stdout) { StringBuilder() }
        buffer.append(text)
        while (true) {
            val end = buffer.indexOf("\n")
            if (end < 0) break
            line(buffer.substring(0, end).trimEnd('\r'))
            buffer.delete(0, end + 1)
        }
    }

    @Synchronized
    fun flush() {
        pending.values.forEach { buffer ->
            if (buffer.isNotEmpty()) line(buffer.toString())
            buffer.setLength(0)
        }
    }

    private fun line(line: String) {
        onLine?.invoke(line)
        val message = line.trim().takeIf { it.startsWith("##teamcity[") }
            ?.let { runCatching { ServiceMessage.parse(it) }.getOrNull() }
        if (message == null) {
            run.appendLog(line)
            return
        }
        if (handle(message)) run.changed()
    }

    private fun handle(message: ServiceMessage): Boolean {
        val attributes = message.attributes
        val name = attributes["name"].orEmpty()
        when (message.messageName) {
            "testCount" -> if (rerun) return false else run.expected = attributes["count"]?.toIntOrNull() ?: return false
            "testSuiteStarted" -> if (rerun) return false else file(attributes["nodeId"] ?: return false, name, attributes["locationHint"])
            "testStarted" -> if (rerun) restarted(attributes, name) else started(attributes, name)
            "testStdOut" -> {
                val mutant = mutant(attributes, name) ?: return false
                MutantStatus.fromMessage(attributes["out"].orEmpty())?.let { mutant.status = it }
            }
            "testFailed" -> {
                val mutant = mutant(attributes, name) ?: return false
                mutant.status = MutantStatus.fromMessage(attributes["message"].orEmpty()) ?: MutantStatus.ESCAPED
                mutant.original = attributes["actual"]
                mutant.mutated = attributes["expected"]
                finish(mutant, attributes)
            }
            "testIgnored" -> {
                val mutant = mutant(attributes, name) ?: return false
                mutant.status = MutantStatus.fromMessage(attributes["message"].orEmpty()) ?: MutantStatus.SKIPPED
                finish(mutant, attributes)
            }
            "testFinished" -> {
                val mutant = mutant(attributes, name) ?: return false
                if (mutant.status == null) {
                    mutant.status = MutantStatus.fromMessage(attributes["message"].orEmpty()) ?: MutantStatus.KILLED
                }
                finish(mutant, attributes)
            }
            else -> return false
        }
        return true
    }

    private fun file(nodeId: String, name: String, hint: String?): MutatedFile = files.getOrPut(nodeId) {
        val path = hint?.let(::parseInfectionLocation)?.file ?: name
        MutatedFile(nodeId, name.ifEmpty { path }, path).also {
            run.files += it
            onFile?.invoke(it)
        }
    }

    private fun started(attributes: Map<String, String>, name: String) {
        val nodeId = attributes["nodeId"] ?: return
        val location = attributes["locationHint"]?.let(::parseInfectionLocation)
        val parent = attributes["parentNodeId"]
        val file = files[parent] ?: file(parent ?: location?.file ?: nodeId, location?.file.orEmpty(), attributes["locationHint"])
        val mutant = Mutant(
            nodeId = nodeId,
            file = file,
            mutatorClass = name.substringBefore(" (").trim(),
            hash = hashOf(name),
            start = location?.start,
            end = location?.end,
        )
        byId[nodeId] = mutant
        byName[name] = mutant
        file.mutants += mutant
    }

    // Reset here only when read back from the archive: a live rerun reset its mutants before starting.
    private fun restarted(attributes: Map<String, String>, name: String) {
        val hash = hashOf(name)
        val mutant = run.mutants.firstOrNull { it.hash == hash } ?: return
        if (mutant.finished) {
            mutant.previousStatus = mutant.status
            mutant.status = null
            mutant.finished = false
        }
        attributes["nodeId"]?.let { byId[it] = mutant }
        byName[name] = mutant
    }

    private fun mutant(attributes: Map<String, String>, name: String): Mutant? =
        attributes["nodeId"]?.let(byId::get) ?: byName[name]

    private fun finish(mutant: Mutant, attributes: Map<String, String>) {
        mutant.durationMs = attributes["duration"]?.toLongOrNull()
        mutant.finished = true
    }

    private fun hashOf(name: String) = name.substringAfter(" (", "").removeSuffix(")")
}
