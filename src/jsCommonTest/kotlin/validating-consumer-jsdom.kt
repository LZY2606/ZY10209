package kotlinx.html.tests

import kotlinx.browser.document
import kotlinx.html.consumers.ValidationDiagnosticKind
import kotlinx.html.consumers.ValidationException
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.validate
import kotlinx.html.dom.JSDOMBuilder
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Shared JS/WasmJS contract test: the same event script must produce the same
 * diagnostics on the DOM consumer as on the recording reference consumer.
 */
class ValidatingConsumerJsDomTest {

    @Test
    fun domBackendStrictModeFailsWithSameDiagnosticAsReferenceRun() {
        val (referenceDiagnostics, _) = runBrokenScriptOverRecording()

        val consumer = JSDOMBuilder<HTMLElement>(document).validate(mode = ValidationMode.STRICT)
        val failure = assertFailsWith<ValidationException> { brokenEventScript(consumer) }

        assertEquals(
            referenceDiagnostics.first().toString(),
            failure.diagnostics.single().toString(),
            "JS/Wasm DOM backend must report the same first diagnostic as the recording reference"
        )
        assertEquals(ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE, failure.diagnostics.single().kind)
    }

    @Test
    fun domBackendCollectModeKeepsForwardingAndReportsUnclosedTags() {
        val consumer = JSDOMBuilder<HTMLElement>(document).validate(mode = ValidationMode.COLLECT)

        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagContent("hello")
        consumer.onTagEnd(div)
        consumer.onTagStart(ScriptTag("span", consumer)) // never closed
        val element = consumer.finalize() // span left open: DOM builder still finalizes

        assertEquals("DIV", element.tagName)
        assertEquals("hello", element.textContent)
        assertEquals(listOf(ValidationDiagnosticKind.UNCLOSED_TAGS), consumer.diagnostics.map { it.kind })
    }
}
