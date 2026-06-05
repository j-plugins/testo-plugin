package com.github.xepozz.testo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the message keys consumed by the action layer
 * (TestoRunCommandAction, TestoNewTestFromClassAction). These read the
 * checked-in TestoBundle.properties directly so they need no IDE platform.
 *
 * Guards against accidental removal/rename of keys the actions format at runtime
 * and against the {0} command placeholder being dropped from action.run.target.command.
 */
class TestoBundleActionMessagesTest {

    private fun props(): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream("messages/TestoBundle.properties")
            ?: error("messages/TestoBundle.properties not found on test classpath")
        val map = LinkedHashMap<String, String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
        return map
    }

    @Test
    fun runTargetKeysExist() {
        val p = props()
        assertTrue(p.containsKey("action.run.target.text"))
        assertTrue(p.containsKey("action.run.target.description"))
        assertTrue(p.containsKey("action.run.target.command"))
    }

    @Test
    fun runTargetCommandKeepsPositionalPlaceholder() {
        // TestoRunCommandAction formats this with the command name; {0} must survive.
        val template = props().getValue("action.run.target.command")
        assertTrue("expected {0} placeholder in: $template", template.contains("{0}"))
        val formatted = java.text.MessageFormat.format(template, "run")
        assertEquals("testo run", formatted)
    }

    @Test
    fun newTestActionLabelsExist() {
        val p = props()
        assertEquals("Testo Test", p.getValue("actions.new.test.action.name"))
        assertTrue(p.containsKey("actions.new.test.action.description"))
    }
}
