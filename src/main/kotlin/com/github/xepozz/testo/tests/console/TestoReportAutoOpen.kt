package com.github.xepozz.testo.tests.console

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

enum class ReportOpenWay { WEB_VIEW, BROWSER }

/** How long an auto-open choice lives. */
enum class AutoOpenScope { THIS_RUN, PROJECT, APPLICATION }

/**
 * When a report opens without being clicked. Every (way, scope) pair is an independent flag, keyed by the report's
 * format and name — its identity across runs, since the path changes with the execution environment. THIS_RUN lives
 * in the run's own [TestoReportStore]; the other two persist through [PropertiesComponent].
 */
object TestoReportAutoOpen {
    fun keyOf(ref: TestoReportRef): String = "${ref.format}/${ref.name.orEmpty()}"

    fun isSet(scope: AutoOpenScope, project: Project, store: TestoReportStore, key: String, way: ReportOpenWay): Boolean =
        when (scope) {
            AutoOpenScope.THIS_RUN -> store.isAutoOpenArmed(key, way)
            AutoOpenScope.PROJECT -> PropertiesComponent.getInstance(project).getBoolean(propertyName(key, way))
            AutoOpenScope.APPLICATION -> PropertiesComponent.getInstance().getBoolean(propertyName(key, way))
        }

    fun set(
        scope: AutoOpenScope,
        project: Project,
        store: TestoReportStore,
        key: String,
        way: ReportOpenWay,
        enabled: Boolean,
    ) {
        when (scope) {
            AutoOpenScope.THIS_RUN -> store.armAutoOpen(key, way, enabled)
            AutoOpenScope.PROJECT -> PropertiesComponent.getInstance(project).setValue(propertyName(key, way), enabled)
            AutoOpenScope.APPLICATION -> PropertiesComponent.getInstance().setValue(propertyName(key, way), enabled)
        }
    }

    /** The ways this report should open on its own — none while muted, whatever any scope grants otherwise. */
    fun decide(project: Project, store: TestoReportStore, ref: TestoReportRef): Set<ReportOpenWay> {
        val key = keyOf(ref)
        if (store.isAutoOpenMuted(key)) return emptySet()
        return ReportOpenWay.entries.filterTo(LinkedHashSet()) { way ->
            AutoOpenScope.entries.any { isSet(it, project, store, key, way) }
        }
    }

    private fun propertyName(key: String, way: ReportOpenWay) = "testo.report.autoOpen.${way.name}.$key"
}
