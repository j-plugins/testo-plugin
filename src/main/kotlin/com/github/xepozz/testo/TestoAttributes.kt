package com.github.xepozz.testo

import com.github.xepozz.testo.php.PhpAttributeView
import com.intellij.openapi.util.text.StringUtil

/**
 * The group names of a `#[Group('db', 'slow')]` attribute, in source order. Read the way an indexer may read them
 * (see [PhpAttributeView.argument]); arguments that are not string literals (constants, concatenations) cannot be
 * resolved there and are skipped, and so are blank names.
 */
fun groupNamesOf(attribute: PhpAttributeView): List<String> = attribute.arguments
    .filterNotNull()
    .filter { it.isStringLiteral }
    .map { StringUtil.unquoteString(it.text) }
    .filter { it.isNotBlank() }
