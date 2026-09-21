package kotlinx.html.tests

import kotlinx.html.consumers.HtmlValidationException
import kotlinx.html.consumers.ValidationKind
import kotlinx.html.consumers.jvmCallSiteSourceToken
import kotlinx.html.consumers.validated
import kotlinx.html.dom.create
import kotlinx.html.stream.appendHTML
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidatorDomContractTest {

    private fun newDocument() = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

    @Test
    fun `JVM DOM consumer produces the contract diagnostics`() {
        val run = ValidatorContract.run(newDocument().create)

        assertEquals(
            ValidatorContract.expectedSummaries,
            run.summaries,
            "JVM DOM consumer diagnostics diverged from the shared contract",
        )
        assertEquals(1, run.downstreamFinalizeCalls, "finalize() must be idempotent")
        assertSame(run.finalizeResult, run.repeatedFinalizeResult)
    }
}

class ValidatorJvmSourceTokenTest {

    @Test
    fun `jvm source token points at the DSL call site`() {
        // the test itself lives in a kotlinx.html.* package, so only the
        // consumer machinery is treated as "library" frames here
        val consumer = StringBuilder().appendHTML(prettyPrint = false).validated {
            sourceToken = jvmCallSiteSourceToken(libraryPackage = "kotlinx.html.consumers")
        }
        consumer.onTagStart(ScriptTag("div", consumer))

        val failure = kotlin.test.assertFailsWith<HtmlValidationException> {
            consumer.onTagEnd(ScriptTag("span", consumer))
        }

        assertEquals(ValidationKind.END_TAG_WITHOUT_START, failure.diagnostic.kind)
        val token = failure.diagnostic.sourceToken
        assertNotNull(token)
        assertTrue(
            token.contains("ValidatorJvmSourceTokenTest"),
            "source token should reference the calling test class, but was: $token",
        )
    }
}
