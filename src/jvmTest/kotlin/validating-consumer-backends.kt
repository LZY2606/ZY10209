package kotlinx.html.tests

import kotlinx.html.consumers.ValidationDiagnosticKind
import kotlinx.html.consumers.ValidationException
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.validate
import kotlinx.html.dom.HTMLDOMBuilder
import kotlinx.html.stream.appendHTML
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatingConsumerJvmBackendsTest {

    @Test
    fun streamBackendStrictModeFailsWithSameDiagnosticAsReferenceRun() {
        val (referenceDiagnostics, _) = runBrokenScriptOverRecording()

        val out = StringWriter()
        val consumer = out.appendHTML(prettyPrint = false).validate(mode = ValidationMode.STRICT)

        val failure = assertFailsWith<ValidationException> { brokenEventScript(consumer) }

        assertEquals(
            referenceDiagnostics.first().toString(),
            failure.diagnostics.single().toString(),
            "JVM stream backend must report the same first diagnostic as the recording reference"
        )
        // the breaking event (duplicate attribute change) was never written to the stream
        assertFalse(out.toString().contains("id=\"b\""), out.toString())
    }

    @Test
    fun streamBackendCollectModeReportsSameDiagnosticsAsReferenceRun() {
        // a stream-tolerant script: no attribute changes (streams cannot apply them),
        // but nesting, void-content, outside-tag and finalize violations included
        val referenceDownstream = RecordingConsumer()
        val reference = referenceDownstream.validate(mode = ValidationMode.COLLECT)
        streamTolerantBrokenScript(reference)

        val out = StringWriter()
        val consumer = out.appendHTML(prettyPrint = false).validate(mode = ValidationMode.COLLECT)
        streamTolerantBrokenScript(consumer)

        assertEquals(
            reference.diagnostics.toString(),
            consumer.diagnostics.toString(),
            "JVM stream backend must report the same diagnostics as the recording reference"
        )
        // downstream event order is preserved: content appears in emission order
        val html = out.toString()
        assertTrue(html.indexOf("hello") < html.indexOf("orphan"), html)
        assertTrue(html.contains("</span>"), html)
    }

    private fun streamTolerantBrokenScript(consumer: kotlinx.html.TagConsumer<*>) {
        fun tag(name: String, empty: Boolean = false) = ScriptTag(name, consumer, empty)

        val div = tag("div")
        consumer.onTagStart(div)
        consumer.onTagContent("hello")
        val br = tag("br", empty = true)
        consumer.onTagStart(br)
        consumer.onTagContent("x") // CONTENT_IN_VOID_ELEMENT
        consumer.onTagEnd(br)
        consumer.onTagEnd(tag("span")) // MISMATCHED_END_TAG
        consumer.onTagEnd(div) // balanced again
        consumer.onTagContent("orphan") // CONTENT_OUTSIDE_TAG
        consumer.onTagStart(tag("section")) // never closed
        consumer.finalize() // UNCLOSED_TAGS
        consumer.onTagContent("late") // EVENT_AFTER_FINALIZE
    }

    @Test
    fun domBackendStrictModeShieldsDomBuilderFromBrokenEvents() {
        val (referenceDiagnostics, _) = runBrokenScriptOverRecording()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

        val consumer = HTMLDOMBuilder(document).validate(mode = ValidationMode.STRICT)
        val failure = assertFailsWith<ValidationException> { brokenEventScript(consumer) }

        assertEquals(
            referenceDiagnostics.first().toString(),
            failure.diagnostics.single().toString(),
            "JVM DOM backend must report the same first diagnostic as the recording reference"
        )
        assertEquals(ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE, failure.diagnostics.single().kind)
    }

    @Test
    fun domBackendCollectModeKeepsForwardingAndReportsUnclosedTags() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val consumer = HTMLDOMBuilder(document).validate(mode = ValidationMode.COLLECT)

        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagContent("hello")
        consumer.onTagEnd(div)
        consumer.onTagStart(ScriptTag("span", consumer)) // never closed
        val element = consumer.finalize() // span left open: DOM builder still finalizes

        assertEquals("div", element.tagName.lowercase())
        assertEquals("hello", element.textContent)
        assertEquals(listOf(ValidationDiagnosticKind.UNCLOSED_TAGS), consumer.diagnostics.map { it.kind })
    }
}
