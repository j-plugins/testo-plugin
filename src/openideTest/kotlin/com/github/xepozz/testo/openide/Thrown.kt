package com.github.xepozz.testo.openide

import org.junit.Assert.fail

/** What [block] throws, of type [T]; fails when it throws nothing and rethrows anything else. */
inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    fail("Expected ${T::class.java.simpleName}")
    error("unreachable")
}
