package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoBundle
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider

/**
 * Names and descriptions for our Code Vision lenses in Settings | Editor | Inlay Hints | Code Vision. Without a
 * [CodeVisionGroupSettingProvider] the platform falls back to `codeLens.<groupId>.name`/`.description` keys in its own
 * bundle, which we don't own — so the entries showed up blank. `groupId` must equal each provider's `id` (a
 * [com.intellij.codeInsight.codeVision.CodeVisionProvider]'s `groupId` defaults to its `id`).
 */
class TestoHistoryCodeVisionGroupSettingProvider : CodeVisionGroupSettingProvider {
    override val groupId: String = "testo.history"
    override val groupName: String get() = TestoBundle.message("testo.codeVision.history.name")
    override val description: String get() = TestoBundle.message("testo.codeVision.history.description")
}

class TestoCoverageByTestCodeVisionGroupSettingProvider : CodeVisionGroupSettingProvider {
    override val groupId: String = "testo.coverage.byTest"
    override val groupName: String get() = TestoBundle.message("testo.coverage.byTest.name")
    override val description: String get() = TestoBundle.message("testo.coverage.byTest.settings.description")
}
