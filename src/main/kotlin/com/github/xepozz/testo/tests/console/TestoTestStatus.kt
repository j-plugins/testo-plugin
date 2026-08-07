package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import javax.swing.Icon

/**
 * The outcome of a single test, mirroring `Testo\Core\Value\Status`.
 *
 * Testo names the case in the `status` attribute of its `testFinished` / `testFailed` / `testIgnored` service
 * messages. The platform only knows passed / failed / ignored, so everything finer — risky, flaky, cancelled,
 * aborted — reaches the IDE through that attribute alone and is lost the moment it is absent: [fromProxy] then
 * collapses the tree state back onto the three statuses the platform can express.
 */
enum class TestoTestStatus(
    /** Value of the `status` attribute, matched case-insensitively against the PHP enum case name. */
    val wireName: String,
    val icon: Icon,
    private val labelKey: String,
) {
    PASSED("passed", TestoIcons.Status.PASSED, "testo.status.passed"),
    FAILED("failed", TestoIcons.Status.FAILED, "testo.status.failed"),
    ERROR("error", TestoIcons.Status.ERROR, "testo.status.error"),
    RISKY("risky", TestoIcons.Status.RISKY, "testo.status.risky"),
    FLAKY("flaky", TestoIcons.Status.FLAKY, "testo.status.flaky"),
    SKIPPED("skipped", TestoIcons.Status.SKIPPED, "testo.status.skipped"),
    CANCELLED("cancelled", TestoIcons.Status.CANCELLED, "testo.status.cancelled"),
    ABORTED("aborted", TestoIcons.Status.ABORTED, "testo.status.aborted"),
    ;

    val displayName: String get() = TestoBundle.message(labelKey)

    /** A run is red when it holds any of these, mirroring `Status::isFailure()` plus the two abandoned outcomes. */
    val isProblem: Boolean get() = this == FAILED || this == ERROR || this == ABORTED

    companion object {
        private val BY_WIRE_NAME = entries.associateBy { it.wireName }

        fun fromWire(raw: String?): TestoTestStatus? =
            raw?.takeIf { it.isNotBlank() }?.let { BY_WIRE_NAME[it.trim().lowercase()] }

        /**
         * Best guess from the tree node alone, for runs where no `status` attribute arrived — an older Testo, or a
         * history import (the platform's history XML keeps the node state, not our attributes). Returns `null` while
         * the test is still running or has not started.
         */
        fun fromProxy(proxy: SMTestProxy): TestoTestStatus? = when {
            proxy.isInProgress -> null
            proxy.isIgnored -> SKIPPED
            proxy.isDefect -> FAILED
            proxy.isPassed -> PASSED
            else -> null
        }
    }
}
