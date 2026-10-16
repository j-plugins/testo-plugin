package com.github.xepozz.testo.coverage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.tree.TreePath

class TestoCoverageSelectOpenedFileTest {

    @Test
    fun newOrReloadedRootIsARebuild() {
        assertTrue(isRootRebuild(null))
        assertTrue(isRootRebuild(TreePath("root")))
    }

    @Test
    fun expandedSubtreeIsNot() {
        assertFalse(isRootRebuild(TreePath(arrayOf("root", "src"))))
    }
}
