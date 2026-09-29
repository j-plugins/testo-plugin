package com.github.xepozz.testo.infection

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage

/**
 * Reads `infection --teamcity` into a [TestoMutationRun]. Process output arrives in arbitrary chunks, so it is cut
 * into lines here, per stream.
 *
 * A mutant's `testStdOut` carries a name but no `nodeId`, so mutants are looked up by name as well.
 */
internal class TestoMutationStream(private val run: TestoMutationRun) {
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
            "testCount" -> run.expected = attributes["count"]?.toIntOrNull() ?: return false
            "testSuiteStarted" -> file(attributes["nodeId"] ?: return false, name, attributes["locationHint"])
            "testStarted" -> started(attributes, name)
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
        MutatedFile(nodeId, name.ifEmpty { path }, path).also { run.files += it }
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
            hash = name.substringAfter(" (", "").removeSuffix(")"),
            start = location?.start,
            end = location?.end,
        )
        byId[nodeId] = mutant
        byName[name] = mutant
        file.mutants += mutant
    }

    private fun mutant(attributes: Map<String, String>, name: String): Mutant? =
        attributes["nodeId"]?.let(byId::get) ?: byName[name]

    private fun finish(mutant: Mutant, attributes: Map<String, String>) {
        mutant.durationMs = attributes["duration"]?.toLongOrNull()
        mutant.finished = true
    }
}
