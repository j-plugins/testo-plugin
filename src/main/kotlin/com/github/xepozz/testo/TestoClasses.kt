package com.github.xepozz.testo

object TestoClasses {
    const val TEST_NEW = "\\Testo\\Attribute\\Test"
    const val TEST_OLD = "\\Testo\\Application\\Attribute\\Test"
    const val TEST_INLINE_OLD = "\\Testo\\Sample\\TestInline"
    const val TEST_INLINE_NEW = "\\Testo\\Inline\\TestInline"

    const val DATA_PROVIDER_OLD = "\\Testo\\Sample\\DataProvider"
    const val DATA_SET_OLD = "\\Testo\\Sample\\DataSet"
    const val DATA_PROVIDER_NEW = "\\Testo\\Data\\DataProvider"
    const val DATA_SET_NEW = "\\Testo\\Data\\DataSet"
    const val DATA_UNION = "\\Testo\\Data\\DataUnion"
    const val DATA_CROSS = "\\Testo\\Data\\DataCross"
    const val DATA_ZIP = "\\Testo\\Data\\DataZip"

    const val BENCH = "\\Testo\\Bench\\Bench"
    const val BENCH_WITH = "\\Testo\\Bench\\BenchWith"

    const val ASSERT = "\\Testo\\Assert"
    const val ASSERTION_EXCEPTION = "\\Testo\\Assert\\State\\Assertion\\AssertionException"
    const val EXPECT = "\\Testo\\Expect"

    val DATA_ATTRIBUTES = arrayOf(
        DATA_PROVIDER_OLD,
        DATA_PROVIDER_NEW,
        DATA_SET_OLD,
        DATA_SET_NEW,
        DATA_UNION,
        DATA_CROSS,
        DATA_ZIP,
    )
    val TEST_ATTRIBUTES = arrayOf(
        TEST_NEW,
        TEST_OLD,
    )
    val TEST_INLINE_ATTRIBUTES = arrayOf(
        TEST_INLINE_OLD,
        TEST_INLINE_NEW,
    )
    val BENCH_ATTRIBUTES = arrayOf(
        BENCH,
        BENCH_WITH,
    )
}