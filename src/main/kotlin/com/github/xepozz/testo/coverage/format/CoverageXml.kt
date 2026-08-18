package com.github.xepozz.testo.coverage.format

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a report into its root [Element], namespace-unaware (so `<file>`/`<line>` match by their literal tag names even
 * under coverage-xml's default namespace) and with all DTD/entity loading off — Cobertura's DOCTYPE points at a remote
 * `coverage-04.dtd` we must never fetch.
 */
internal fun readXmlRoot(path: Path): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    return Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it).documentElement }
}

internal fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

internal fun Element.childElements(tag: String): List<Element> = childElements().filter { it.tagName == tag }

internal fun Element.descendants(tag: String): List<Element> =
    getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
