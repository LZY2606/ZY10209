package kotlinx.html.tests

import kotlinx.html.Entities
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.Unsafe
import kotlinx.html.consumers.PredicateResults
import kotlinx.html.consumers.ValidatingTagConsumer
import kotlinx.html.consumers.ValidationDiagnostic
import kotlinx.html.consumers.ValidationDiagnosticKind
import kotlinx.html.consumers.ValidationException
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.filter
import kotlinx.html.consumers.validate
import kotlinx.html.org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records every event so tests can assert that downstream order is preserved. */
class RecordingConsumer : TagConsumer<List<String>> {
    val events = mutableListOf<String>()

    override fun onTagStart(tag: Tag) {
        events += "start:${tag.tagName}"
    }

    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {
        events += "attr:${tag.tagName}.$attribute=$value"
    }

    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {
        events += "event:${tag.tagName}.$event"
    }

    override fun onTagEnd(tag: Tag) {
        events += "end:${tag.tagName}"
    }

    override fun onTagContent(content: CharSequence) {
        events += "text:$content"
    }

    override fun onTagContentEntity(entity: Entities) {
        events += "entity:${entity.text}"
    }

    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) {
        events += "unsafe"
    }

    override fun onTagComment(content: CharSequence) {
        events += "comment:$content"
    }

    override fun finalize(): List<String> {
        events += "finalize"
        return events
    }

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = null
}

private data class Entry(override val key: String, override val value: String) : Map.Entry<String, String>

/** A hand-written tag so tests can emit event sequences the DSL builders would never produce. */
class ScriptTag(
    override val tagName: String,
    override val consumer: TagConsumer<*>,
    override val emptyTag: Boolean = false,
    attrs: List<Pair<String, String>> = emptyList(),
) : Tag {
    override val namespace: String? get() = null
    override val inlineTag: Boolean get() = false

    private val backing = LinkedHashMap<String, String>().apply {
        attrs.forEach { put(it.first, it.second) }
    }

    private val entries = attrs.map { Entry(it.first, it.second) }

    override val attributes: MutableMap<String, String> get() = backing
    override val attributesEntries: Collection<Map.Entry<String, String>>
        get() = entries
}

/**
 * The single event script shared by all backend contract tests. It emits, in order:
 * a duplicate attribute change, an attribute change after content, content inside a void
 * element, a mismatched end tag, content outside any tag, duplicate attributes at start,
 * an unclosed tag at finalize and a write after finalize.
 */
fun brokenEventScript(consumer: TagConsumer<*>) {
    fun tag(name: String, empty: Boolean = false, attrs: List<Pair<String, String>> = emptyList()) =
        ScriptTag(name, consumer, empty, attrs)

    val div = tag("div")
    consumer.onTagStart(div)
    consumer.onTagAttributeChange(div, "id", "a")
    consumer.onTagAttributeChange(div, "id", "b") // DUPLICATE_ATTRIBUTE
    consumer.onTagContent("hello")
    consumer.onTagAttributeChange(div, "class", "x") // ATTRIBUTE_AFTER_CONTENT

    val br = tag("br", empty = true)
    consumer.onTagStart(br)
    consumer.onTagContent("x") // CONTENT_IN_VOID_ELEMENT
    consumer.onTagEnd(br)

    consumer.onTagContentEntity(Entities.nbsp)
    consumer.onTagContentUnsafe { +"raw" }
    consumer.onTagComment("note")

    consumer.onTagEnd(tag("span")) // MISMATCHED_END_TAG
    consumer.onTagEnd(div) // balanced again: validator does not resync, but names match here

    consumer.onTagContent("orphan") // CONTENT_OUTSIDE_TAG

    consumer.onTagStart(tag("dup", attrs = listOf("a" to "1", "a" to "2"))) // DUPLICATE_ATTRIBUTE
    consumer.finalize() // UNCLOSED_TAGS (dup left open)
    consumer.onTagContent("late") // EVENT_AFTER_FINALIZE
}

/** Diagnostics every backend must report for [brokenEventScript] in COLLECT mode. */
val expectedBrokenScriptDiagnostics = listOf(
    ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE,
    ValidationDiagnosticKind.ATTRIBUTE_AFTER_CONTENT,
    ValidationDiagnosticKind.CONTENT_IN_VOID_ELEMENT,
    ValidationDiagnosticKind.MISMATCHED_END_TAG,
    ValidationDiagnosticKind.CONTENT_OUTSIDE_TAG,
    ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE,
    ValidationDiagnosticKind.UNCLOSED_TAGS,
    ValidationDiagnosticKind.EVENT_AFTER_FINALIZE,
)

/** Events the downstream must see for [brokenEventScript] in COLLECT mode (order preserved). */
val expectedBrokenScriptDownstreamEvents = listOf(
    "start:div",
    "attr:div.id=a",
    "attr:div.id=b",
    "text:hello",
    "attr:div.class=x",
    "start:br",
    "text:x",
    "end:br",
    "entity:${Entities.nbsp.text}",
    "unsafe",
    "comment:note",
    "end:span",
    "end:div",
    "text:orphan",
    "start:dup",
    "finalize",
    "text:late",
)

/** Runs [brokenEventScript] over a [RecordingConsumer] in COLLECT mode and returns
 * the produced diagnostics together with the forwarded downstream events. Backend
 * contract tests compare their own diagnostics against this reference run. */
fun runBrokenScriptOverRecording(): Pair<List<ValidationDiagnostic>, List<String>> {
    val downstream = RecordingConsumer()
    val consumer = downstream.validate(mode = ValidationMode.COLLECT)
    brokenEventScript(consumer)
    return consumer.diagnostics to downstream.events
}

class ValidatingConsumerContractTest {

    @Test
    fun collectModeRecordsAllDiagnosticsAndForwardsEveryEventInOrder() {
        val (diagnostics, events) = runBrokenScriptOverRecording()

        assertEquals(
            expectedBrokenScriptDiagnostics,
            diagnostics.map { it.kind },
            "diagnostic kinds mismatch:\n" + diagnostics.joinToString("\n")
        )
        assertEquals(expectedBrokenScriptDownstreamEvents, events)
    }

    @Test
    fun diagnosticsCarryLocatableContext() {
        val downstream = RecordingConsumer()
        var tokenCounter = 0
        val consumer = downstream.validate(mode = ValidationMode.COLLECT) { "token-${tokenCounter++}" }

        brokenEventScript(consumer)

        val mismatched = consumer.diagnostics.first { it.kind == ValidationDiagnosticKind.MISMATCHED_END_TAG }
        assertEquals("onTagEnd(span)", mismatched.event)
        assertEquals(listOf("div"), mismatched.tagStack)
        assertEquals("token-3", mismatched.sourceToken)
        assertEquals(false, mismatched.finalized)
        assertTrue(mismatched.forwardedEvents > 0, "forwarded event count must be recorded")
        val rendered = mismatched.toString()
        assertTrue("MISMATCHED_END_TAG" in rendered, rendered)
        assertTrue("div" in rendered, rendered)
        assertTrue("token-3" in rendered, rendered)
    }

    @Test
    fun strictModeFailsBeforeForwardingTheBreakingEvent() {
        val downstream = RecordingConsumer()
        val consumer = downstream.validate(mode = ValidationMode.STRICT)

        val failure = assertFailsWith<ValidationException> { brokenEventScript(consumer) }

        assertEquals(ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE, failure.diagnostics.single().kind)
        // the breaking event itself was never forwarded
        assertEquals(listOf("start:div", "attr:div.id=a"), downstream.events)
    }

    @Test
    fun cleanScriptProducesNoDiagnostics() {
        val downstream = RecordingConsumer()
        val consumer = downstream.validate(mode = ValidationMode.STRICT)

        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagAttributeChange(div, "id", "a")
        consumer.onTagContent("hello")
        consumer.onTagContentEntity(Entities.nbsp)
        consumer.onTagContentUnsafe { +"raw" }
        consumer.onTagComment("note")
        val br = ScriptTag("br", consumer, emptyTag = true)
        consumer.onTagStart(br)
        consumer.onTagEnd(br)
        consumer.onTagEnd(div)
        consumer.finalize()

        assertTrue(consumer.diagnostics.isEmpty())
        consumer.finalize() // idempotent, no diagnostics, no extra downstream call
        assertEquals(1, downstream.events.count { it == "finalize" })
    }

    @Test
    fun collectModeDiagnosticsAreBounded() {
        val downstream = RecordingConsumer()
        val consumer = downstream.validate(mode = ValidationMode.COLLECT, maxDiagnostics = 2)

        repeat(5) { consumer.onTagContent("orphan-$it") }

        assertEquals(2, consumer.diagnostics.size)
        assertEquals(3, consumer.droppedDiagnosticCount)
    }

    @Test
    fun finalizeIsIdempotentAndCachesDownstreamResult() {
        val downstream = RecordingConsumer()
        val consumer = downstream.validate(mode = ValidationMode.STRICT)
        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagEnd(div)

        val first = consumer.finalize()
        val second = consumer.finalize()
        assertSame(first, second)
        assertEquals(1, downstream.events.count { it == "finalize" })
    }

    @Test
    fun strictFinalizeFailureIsIdempotent() {
        val downstream = RecordingConsumer()
        val consumer = downstream.validate(mode = ValidationMode.STRICT)
        consumer.onTagStart(ScriptTag("div", consumer)) // never closed

        val first = assertFailsWith<ValidationException> { consumer.finalize() }
        val second = assertFailsWith<ValidationException> { consumer.finalize() }
        assertSame(first, second)
        assertEquals(ValidationDiagnosticKind.UNCLOSED_TAGS, first.diagnostics.single().kind)
        // downstream finalize was never called: the structural failure happened first
        assertTrue(downstream.events.none { it == "finalize" })
    }

    @Test
    fun downstreamFinalizeExceptionIsPropagatedNotConvertedAndCached() {
        val boom = IllegalStateException("downstream is broken")
        val throwing = object : TagConsumer<List<String>> by RecordingConsumer() {
            var calls = 0
            override fun finalize(): List<String> {
                calls++
                throw boom
            }
        }
        val consumer = throwing.validate(mode = ValidationMode.COLLECT)
        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)
        consumer.onTagEnd(div)

        val first = assertFailsWith<IllegalStateException> { consumer.finalize() }
        val second = assertFailsWith<IllegalStateException> { consumer.finalize() }
        assertSame(boom, first)
        assertSame(boom, second)
        assertEquals(1, throwing.calls, "downstream finalize must be called exactly once")
        assertTrue(consumer.diagnostics.isEmpty(), "downstream exceptions must not become diagnostics")
    }

    @Test
    fun downstreamEventExceptionLeavesValidatorStateConsistent() {
        val boom = RuntimeException("cannot write")
        val throwing = object : TagConsumer<List<String>> by RecordingConsumer() {
            override fun onTagContent(content: CharSequence) {
                throw boom
            }
        }
        val consumer = throwing.validate(mode = ValidationMode.STRICT)
        val div = ScriptTag("div", consumer)
        consumer.onTagStart(div)

        assertSame(boom, assertFailsWith<RuntimeException> { consumer.onTagContent("x") })
        assertTrue(consumer.diagnostics.isEmpty())
        // the failed event was not counted as forwarded; the tag is still open
        val failure = assertFailsWith<ValidationException> { consumer.finalize() }
        assertEquals(ValidationDiagnosticKind.UNCLOSED_TAGS, failure.diagnostics.single().kind)
        assertEquals(1, failure.diagnostics.single().forwardedEvents)
    }

    @Test
    fun wrapperOrderDecidesWhichEventsAreValidated() {
        // validate() outside filter(): raw events are validated
        val rawDownstream = RecordingConsumer()
        val rawValidated = rawDownstream
            .filter { if (it.tagName == "span") PredicateResults.DROP else PredicateResults.PASS }
            .validate(mode = ValidationMode.COLLECT)

        // validate() inside filter(): transformed (filtered) events are validated
        val filteredDownstream = RecordingConsumer()
        val innerValidator = filteredDownstream.validate(mode = ValidationMode.COLLECT)
        val filteredValidated = innerValidator
            .filter { if (it.tagName == "span") PredicateResults.DROP else PredicateResults.PASS }

        fun TagConsumer<*>.emit() {
            val div = ScriptTag("div", this)
            val span = ScriptTag("span", this)
            val b = ScriptTag("b", this)
            onTagStart(div)
            onTagStart(span)
            onTagStart(b)
            onTagEnd(span) // mismatched in the raw stream: expected </b>
            onTagEnd(b)
            onTagEnd(div) // mismatched in the raw stream: span is still open
            finalize()
        }

        rawValidated.emit()
        filteredValidated.emit()

        assertEquals(
            listOf(
                ValidationDiagnosticKind.MISMATCHED_END_TAG,
                ValidationDiagnosticKind.MISMATCHED_END_TAG,
                ValidationDiagnosticKind.UNCLOSED_TAGS,
            ),
            rawValidated.diagnostics.map { it.kind },
            "raw stream diagnostics mismatch:\n" + rawValidated.diagnostics.joinToString("\n")
        )
        // the filter drops the whole span subtree (including the broken events), so the
        // transformed stream reaching the inner validator is balanced
        assertTrue(
            innerValidator.diagnostics.isEmpty(),
            "filtered stream must be balanced:\n" + innerValidator.diagnostics.joinToString("\n")
        )
        assertEquals(listOf("start:div", "end:div", "finalize"), filteredDownstream.events)
    }
}
