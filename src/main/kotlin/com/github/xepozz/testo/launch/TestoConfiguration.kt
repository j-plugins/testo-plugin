package com.github.xepozz.testo.launch

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider

/**
 * A Testo run configuration as the rest of the plugin sees it, whichever IDE's configuration class stands behind it.
 * The run is read and changed through [selection]; the IDE's own settings stay the configuration's business.
 */
interface TestoConfiguration : RunConfiguration, SMRunnerConsolePropertiesProvider {
    /** What the configuration runs. Assigning writes every field back into the configuration's saved settings. */
    var selection: TestoRunSelection

    /** The interpreter the run uses, for the run archive; empty when there is none. */
    val interpreterName: String

    /** The kind of that interpreter (`local`, a remote connection type), for the run archive; empty when there is none. */
    val interpreterKind: String

    companion object {
        /** The executor a Coverage run is started with; the run history tells runs apart by it. */
        const val COVERAGE_EXECUTOR_ID = "Coverage"
    }
}
