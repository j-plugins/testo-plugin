package com.github.xepozz.testo.index

import com.intellij.util.indexing.FileBasedIndex

/**
 * PHP files, recognised by the file type's name rather than its class: every PHP plugin registers its own file type
 * class, and all of them name it "PHP" (the plugin's own `fileType name="PHP"` registration relies on that too).
 */
internal val PHP_INPUT_FILTER = FileBasedIndex.InputFilter { it.fileType.name == "PHP" }
