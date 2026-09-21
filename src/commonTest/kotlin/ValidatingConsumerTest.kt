package kotlinx.html.tests

import kotlinx.html.TagConsumer
import kotlinx.html.br
import kotlinx.html.consumers.HtmlValidationException
import kotlinx.html.consumers.PredicateResults
import kotlinx.html.consumers.ValidationKind
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.filter
import kotlinx.html.consumers.onFinalize
import kotlinx.html.consumers.trace
import kotlinx.html.consumers.validated
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.stream.appendHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidatingConsumerTest {

    @Test
    fun validDslOutputIsIdenticalWithAndWithoutValidation() {
        fun build(validate: Boolean): String {
            val out = StringBuilder()
            val base = out.appendHTML(prettyPrint = false)
            val consumer = if (validate) base.validated() else base
            consumer.div {
                p {
                    +"hello"
                }
            }
            return out.toString()
        }

        assertEquals(build(validate = false), build(validate = true))
    }

    @Test
    fun strictModeFailsBeforeForwardingStructureBreakingEvent() {
        val out = StringBuilder()
        val consumer = out.appendHTML(prettyPrint = false).validated()

        consumer.onTagStart(ScriptTag("div", consumer))
        consumer.onTagStart(ScriptTag("p", consumer))

        val failure = assertFailsWith<HtmlValidationException> {
            consumer.onTagEnd(ScriptTag("div", consumer))
        }
        assertEquals(ValidationKind.MISMATCHED_END_TAG, failure.diagnostic.kind)
        assertEquals(listOf("div", "p"), failure.diagnostic.tagStack)

        // the mismatched end tag was not forwarded: only the flushed start tag reached the stream
        assertEquals("<div>", out.toString())
    }

    @Test
    fun strictModeReportsContentInsideVoidElement() {
        val consumer = StringBuilder().appendHTML(prettyPrint = false).validated()

        val failure = assertFailsWith<HtmlValidationException> {
            consumer.br {
                +"not allowed here"
            }
        }
        assertEquals(ValidationKind.CONTENT_IN_VOID_ELEMENT, failure.diagnostic.kind)
    }

    @Test
    fun strictModeFailsFinalizeWithUnclosedTags() {
        var downstreamFinalized = false
        val downstream = object : TagConsumer<Unit> by NoopConsumer {
            override fun finalize() {
                downstreamFinalized = true
            }
        }
        val consumer = downstream.validated()
        consumer.onTagStart(ScriptTag("div", consumer))

        val failure = assertFailsWith<HtmlValidationException> { consumer.finalize() }
        assertEquals(ValidationKind.UNCLOSED_TAGS_AT_FINALIZE, failure.diagnostic.kind)
        assertEquals(false, downstreamFinalized)
    }

    @Test
    fun strictModeRejectsWritesAfterFinalize() {
        val consumer = StringBuilder().appendHTML(prettyPrint = false).validated()
        consumer.div { +"ok" }
        consumer.finalize()

        val failure = assertFailsWith<HtmlValidationException> {
            consumer.onTagContent("too late")
        }
        assertEquals(ValidationKind.EVENT_AFTER_FINALIZE, failure.diagnostic.kind)
    }

    @Test
    fun collectModeIsTransparentForDownstream() {
        fun drive(consumer: TagConsumer<*>) {
            val div = ScriptTag("div", consumer)
            consumer.onTagStart(div)
            consumer.onTagAttributeChange(div, "id", "a")
            consumer.onTagAttributeChange(div, "id", "b")
            consumer.onTagEnd(div)
            consumer.onTagEnd(ScriptTag("ghost", consumer))
            consumer.finalize()
        }

        val plain = StringBuilder().also { drive(it.appendHTML(prettyPrint = false)) }
        val validatedOut = StringBuilder()
        val validating = validatedOut.appendHTML(prettyPrint = false).validated { mode = ValidationMode.COLLECT }
        drive(validating)

        assertEquals(plain.toString(), validatedOut.toString())
        assertEquals(
            listOf(ValidationKind.DUPLICATE_ATTRIBUTE, ValidationKind.END_TAG_WITHOUT_START),
            validating.diagnostics.map { it.kind },
        )
    }

    @Test
    fun collectModeRecordsBoundedDiagnostics() {
        val consumer = NoopConsumer.validated {
            mode = ValidationMode.COLLECT
            maxDiagnostics = 2
        }
        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        repeat(5) { index ->
            consumer.onTagAttributeChange(div, "id", "value-$index")
        }

        // the first assignment is not a duplicate; the remaining four are, capped at maxDiagnostics
        assertEquals(2, consumer.diagnostics.size)
        assertEquals(2, consumer.droppedDiagnosticCount)
    }

    @Test
    fun ignoredKindsAreNeitherRecordedNorThrown() {
        val seen = mutableListOf<ValidationKind>()
        val consumer = NoopConsumer.validated {
            mode = ValidationMode.COLLECT
            ignoredKinds += ValidationKind.DUPLICATE_ATTRIBUTE
            onDiagnostic = { seen += it.kind }
        }
        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagAttributeChange(div, "id", "a")
        consumer.onTagAttributeChange(div, "id", "b")
        consumer.onTagEnd(ScriptTag("ghost", consumer))

        assertEquals(listOf(ValidationKind.END_TAG_WITHOUT_START), seen)
        assertEquals(listOf(ValidationKind.END_TAG_WITHOUT_START), consumer.diagnostics.map { it.kind })
    }

    @Test
    fun finalizeIsIdempotentAndRunsDownstreamOnce() {
        var finalizeCalls = 0
        val downstream = object : TagConsumer<String> by NoopConsumerWithStringResult {
            override fun finalize(): String {
                finalizeCalls++
                return "done"
            }
        }
        val consumer = downstream.validated()
        consumer.onTagStart(ScriptTag("div", consumer))
        consumer.onTagEnd(ScriptTag("div", consumer))

        assertEquals("done", consumer.finalize())
        assertEquals("done", consumer.finalize())
        assertEquals(1, finalizeCalls)
    }

    @Test
    fun finalizeAfterDownstreamFailureReplaysSameException() {
        var finalizeCalls = 0
        val failure = RuntimeException("downstream is broken")
        val downstream = object : TagConsumer<Unit> by NoopConsumer {
            override fun finalize() {
                finalizeCalls++
                throw failure
            }
        }
        val consumer = downstream.validated()
        consumer.onTagStart(ScriptTag("div", consumer))
        consumer.onTagEnd(ScriptTag("div", consumer))

        val first = assertFailsWith<RuntimeException> { consumer.finalize() }
        val second = assertFailsWith<RuntimeException> { consumer.finalize() }
        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(1, finalizeCalls)
        assertTrue(consumer.diagnostics.isEmpty(), "downstream failures must not become structural diagnostics")
    }

    @Test
    fun downstreamEventFailuresPropagateUnchanged() {
        val failure = IllegalStateException("cannot write content")
        val downstream = object : TagConsumer<Unit> by NoopConsumer {
            override fun onTagContent(content: CharSequence) {
                throw failure
            }
        }
        val consumer = downstream.validated()
        consumer.onTagStart(ScriptTag("div", consumer))

        val thrown = assertFailsWith<IllegalStateException> {
            consumer.onTagContent("boom")
        }
        assertSame(failure, thrown)
        assertTrue(consumer.diagnostics.isEmpty())
    }

    @Test
    fun diagnosticsFollowEventStreamAtWrapperPosition() {
        fun drive(consumer: TagConsumer<*>) {
            val a = ScriptTag("a", consumer)
            val b = ScriptTag("b", consumer)
            consumer.onTagStart(a)
            consumer.onTagStart(b)
            consumer.onTagEnd(a)
            consumer.onTagEnd(b)
        }

        // validator sees the original events when it wraps the filter:
        // events flow validator -> filter -> stream
        val originalEventsValidator = StringBuilder().appendHTML(prettyPrint = false)
            .filter { if (it.tagName == "b") PredicateResults.DROP else PredicateResults.PASS }
            .validated { mode = ValidationMode.COLLECT }
        drive(originalEventsValidator)

        // validator sees the transformed events when the filter wraps it:
        // events flow filter -> validator -> stream, so the dropped <b> never reaches it
        val transformedEventsValidator = StringBuilder().appendHTML(prettyPrint = false)
            .validated { mode = ValidationMode.COLLECT }
        drive(transformedEventsValidator.filter { if (it.tagName == "b") PredicateResults.DROP else PredicateResults.PASS })

        assertEquals(
            listOf(ValidationKind.MISMATCHED_END_TAG, ValidationKind.END_TAG_WITHOUT_START),
            originalEventsValidator.diagnostics.map { it.kind },
        )
        assertEquals(
            listOf(ValidationKind.END_TAG_WITHOUT_START),
            transformedEventsValidator.diagnostics.map { it.kind },
        )
    }

    @Test
    fun composesWithTraceAndOnFinalize() {
        val lines = mutableListOf<String>()
        var finalizedWith: Pair<Any?, Boolean>? = null

        val consumer = StringBuilder().appendHTML(prettyPrint = false)
            .validated()
            .trace { lines += it }
            .onFinalize { result, partial -> finalizedWith = result to partial }

        consumer.div {
            p { +"hello" }
        }
        consumer.finalize()

        assertTrue(lines.any { it.contains("open div") }, "trace should see the start events: $lines")
        assertTrue(lines.any { it.contains("close p") }, "trace should see the end events: $lines")
        assertEquals(false, finalizedWith?.second)
    }

    @Test
    fun onFinalizeObservesPartialOutputInCollectMode() {
        var partial: Boolean? = null
        val consumer = StringBuilder().appendHTML(prettyPrint = false)
            .validated { mode = ValidationMode.COLLECT }
            .onFinalize { _, p -> partial = p }

        consumer.onTagStart(ScriptTag("div", consumer))
        consumer.finalize()

        assertEquals(true, partial)
    }
}

internal object NoopConsumer : TagConsumer<Unit> {
    override fun onTagStart(tag: kotlinx.html.Tag) {}
    override fun onTagAttributeChange(tag: kotlinx.html.Tag, attribute: String, value: String?) {}
    override fun onTagEvent(tag: kotlinx.html.Tag, event: String, value: (kotlinx.html.org.w3c.dom.events.Event) -> Unit) {}
    override fun onTagEnd(tag: kotlinx.html.Tag) {}
    override fun onTagContent(content: CharSequence) {}
    override fun onTagContentEntity(entity: kotlinx.html.Entities) {}
    override fun onTagContentUnsafe(block: kotlinx.html.Unsafe.() -> Unit) {}
    override fun onTagComment(content: CharSequence) {}
    override fun finalize() {}

    @kotlinx.html.ExperimentalKotlinxHtmlApi
    override val head: Any? get() = null
}

internal object NoopConsumerWithStringResult : TagConsumer<String> {
    override fun onTagStart(tag: kotlinx.html.Tag) {}
    override fun onTagAttributeChange(tag: kotlinx.html.Tag, attribute: String, value: String?) {}
    override fun onTagEvent(tag: kotlinx.html.Tag, event: String, value: (kotlinx.html.org.w3c.dom.events.Event) -> Unit) {}
    override fun onTagEnd(tag: kotlinx.html.Tag) {}
    override fun onTagContent(content: CharSequence) {}
    override fun onTagContentEntity(entity: kotlinx.html.Entities) {}
    override fun onTagContentUnsafe(block: kotlinx.html.Unsafe.() -> Unit) {}
    override fun onTagComment(content: CharSequence) {}
    override fun finalize(): String = "noop"

    @kotlinx.html.ExperimentalKotlinxHtmlApi
    override val head: Any? get() = null
}
