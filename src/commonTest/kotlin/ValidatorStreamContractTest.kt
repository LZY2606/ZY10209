package kotlinx.html.tests

import kotlinx.html.stream.appendHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Runs the shared [ValidatorContract] event script against the stream consumer.
 * The same expectations are asserted for the DOM consumers on JVM, JS and Wasm,
 * proving that one event script yields one identical set of diagnostics.
 */
class ValidatorStreamContractTest {

    @Test
    fun streamConsumerProducesContractDiagnostics() {
        val out = StringBuilder()
        val run = ValidatorContract.run(out.appendHTML(prettyPrint = false))

        assertEquals(
            ValidatorContract.expectedSummaries,
            run.summaries,
            "stream consumer diagnostics diverged from the shared contract",
        )
        assertEquals(1, run.downstreamFinalizeCalls, "finalize() must be idempotent")
        assertSame(run.finalizeResult, run.repeatedFinalizeResult)
    }
}
