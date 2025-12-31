package com.github.xepozz.testo

import com.intellij.spellchecker.BundledDictionaryProvider

class SpellcheckingDictionaryProvider : BundledDictionaryProvider {
    override fun getBundledDictionaries(): Array<String> = arrayOf("/testo.dic")
}