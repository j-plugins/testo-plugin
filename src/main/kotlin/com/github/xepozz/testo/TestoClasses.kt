package com.github.xepozz.testo

object TestoClasses {
    const val TEST = "\\Testo\\Test"
    const val TEST_INLINE = "\\Testo\\Inline\\TestInline"

    const val DATA_PROVIDER = "\\Testo\\Data\\DataProvider"
    const val DATA_SET = "\\Testo\\Data\\DataSet"
    const val DATA_UNION = "\\Testo\\Data\\DataUnion"
    const val DATA_CROSS = "\\Testo\\Data\\DataCross"
    const val DATA_ZIP = "\\Testo\\Data\\DataZip"

    const val BENCH = "\\Testo\\Bench"

    const val APPLICATION_CONFIG = "\\Testo\\Application\\Config\\ApplicationConfig"
    const val SUITE_CONFIG = "\\Testo\\Application\\Config\\SuiteConfig"

    const val ASSERT = "\\Testo\\Assert"
    const val ASSERTION_EXCEPTION = "\\Testo\\Assert\\State\\Assertion\\AssertionException"
    const val EXPECT = "\\Testo\\Expect"

    val DATA_ATTRIBUTES = arrayOf(
        DATA_PROVIDER,
        DATA_SET,
        DATA_UNION,
        DATA_CROSS,
        DATA_ZIP,
    )
    val TEST_ATTRIBUTES = arrayOf(
        TEST,
        TEST_INLINE,
    )
    val TEST_INLINE_ATTRIBUTES = arrayOf(
        TEST_INLINE,
    )
    val BENCH_ATTRIBUTES = arrayOf(
        BENCH,
    )
}