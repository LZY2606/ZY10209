package kotlinx.html.tests

import kotlinx.browser.document
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.validated
import kotlinx.html.div
import kotlinx.html.dom.create
import kotlinx.html.injector.InjectByTagName
import kotlinx.html.injector.inject
import kotlinx.html.span
import org.w3c.dom.HTMLElement
import kotlin.properties.Delegates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runs the shared [ValidatorContract] event script against the JS/Wasm DOM
 * consumer. This source set is shared between the JS and the WasmJs test
 * compilations, so both targets assert the same diagnostics as the JVM tests.
 */
class ValidatorJsDomContractTest {

    @Test
    fun domConsumerProducesContractDiagnostics() {
        val run = ValidatorContract.run(document.create)

        assertEquals(
            ValidatorContract.expectedSummaries,
            run.summaries,
            "JS/Wasm DOM consumer diagnostics diverged from the shared contract",
        )
        assertEquals(1, run.downstreamFinalizeCalls, "finalize() must be idempotent")
        assertSame(run.finalizeResult, run.repeatedFinalizeResult)
    }
}

private class SpanCapture {
    var node: HTMLElement by Delegates.notNull()
}

class ValidatorInjectorCompositionTest {

    @Test
    fun validatorComposesWithInjector() {
        val bean = SpanCapture()
        val consumer = document.create
            .inject(bean, listOf(InjectByTagName("span") to SpanCapture::node))
            .validated { mode = ValidationMode.COLLECT }

        consumer.div {
            span {
                +"captured"
            }
        }
        consumer.finalize()

        assertTrue(consumer.diagnostics.isEmpty(), "valid composition must not produce diagnostics")
        assertEquals("SPAN", bean.node.tagName)
        assertEquals("captured", bean.node.textContent)
    }
}
