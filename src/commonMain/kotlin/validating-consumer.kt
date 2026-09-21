package kotlinx.html.consumers

import kotlinx.html.*
import kotlinx.html.org.w3c.dom.events.Event

/**
 * How a [ValidatingTagConsumer] reacts to a structure-breaking event.
 */
enum class ValidationMode {
    /**
     * The first structural problem aborts the stream: a [ValidationException] is thrown
     * *before* the offending event is forwarded downstream, so the downstream consumer
     * never observes an event that violates the structural contract.
     */
    STRICT,

    /**
     * Diagnostics are recorded (bounded by `maxDiagnostics`) and every event is still
     * forwarded downstream unchanged, so the wrapper stays transparent. The validator
     * does not try to resynchronize after a broken event (e.g. a mismatched end tag does
     * not pop the stack), so a single mistake may legitimately cause follow-up diagnostics.
     */
    COLLECT,
}

/**
 * Kinds of structural problems detected by [ValidatingTagConsumer].
 */
enum class ValidationDiagnosticKind {
    /** `onTagEnd` arrived while no tag was open. */
    END_TAG_WITHOUT_OPEN_TAG,

    /** `onTagEnd` name does not match the innermost open tag. No implicit repair is attempted. */
    MISMATCHED_END_TAG,

    /** `finalize()` was called while tags were still open. */
    UNCLOSED_TAGS,

    /**
     * An attribute name appeared twice for the same tag: either duplicated inside
     * [Tag.attributesEntries] at `onTagStart`, or set again via `onTagAttributeChange`
     * after it already had a value.
     */
    DUPLICATE_ATTRIBUTE,

    /** Character content, an entity, an unsafe block, a comment or an attribute change
     * arrived while no tag was open. */
    CONTENT_OUTSIDE_TAG,

    /** Content (text, entity, unsafe block or comment) was emitted inside a void element
     * ([Tag.emptyTag] == true). Void elements cannot have children. */
    CONTENT_IN_VOID_ELEMENT,

    /** `onTagAttributeChange` arrived after content was already emitted inside the tag.
     * Stream-based consumers cannot honour such a change. */
    ATTRIBUTE_AFTER_CONTENT,

    /** Any event arrived after [TagConsumer.finalize] was invoked. */
    EVENT_AFTER_FINALIZE,
}

/**
 * A single structural problem, together with enough context to locate it:
 * the offending event, a snapshot of the open tag stack, an optional lightweight
 * source token supplied by the caller, and the downstream output state at the moment
 * the problem was detected.
 */
class ValidationDiagnostic(
    val kind: ValidationDiagnosticKind,
    /** Human readable description of the event being processed, e.g. `onTagEnd(div)`. */
    val event: String,
    /** What exactly is wrong. */
    val message: String,
    /** Open tags at the moment of the problem, outermost first. */
    val tagStack: List<String>,
    /** Optional lightweight token identifying the DSL call source (see `sourceToken`). */
    val sourceToken: String?,
    /** Number of events successfully forwarded downstream so far. */
    val forwardedEvents: Long,
    /** Whether `finalize()` had already been invoked. */
    val finalized: Boolean,
) {
    override fun toString(): String = buildString {
        append(kind).append(" at ").append(event).append(": ").append(message)
        append(" | stack: ").append(if (tagStack.isEmpty()) "<empty>" else tagStack.joinToString(" > "))
        append(" | downstream: ").append(forwardedEvents).append(" events forwarded")
        append(", finalized=").append(finalized)
        if (sourceToken != null) append(" | source: ").append(sourceToken)
    }
}

/**
 * Thrown by [ValidatingTagConsumer] in [ValidationMode.STRICT] before a structure-breaking
 * event is forwarded downstream, or by `finalize()` when tags are left open.
 */
class ValidationException(
    val diagnostics: List<ValidationDiagnostic>,
) : RuntimeException(
    "HTML stream validation failed with ${diagnostics.size} diagnostic(s); first: ${diagnostics.firstOrNull()}"
)

private class OpenTagFrame(
    val name: String,
    val isVoid: Boolean,
    val attributeNames: MutableSet<String>,
) {
    var contentSeen: Boolean = false
}

/**
 * A [TagConsumer] wrapper that validates the structural well-formedness of the event
 * stream without building a DOM: tag nesting, end-tag pairing, duplicate attributes,
 * content placement and writes after close.
 *
 * Composition: the validator only sees the events that reach it, so wrapper order decides
 * which stream is validated. `consumer.filter { ... }.validate()` validates the *raw* events
 * (before filtering), while `consumer.validate().filter { ... }` validates the *transformed*
 * events produced by the filter. The same applies to [delayed], [onFinalize], [trace] and
 * the injector: diagnostics always describe the events as seen at the validator's position
 * in the chain, and downstream events keep their original order.
 *
 * HTML note: this is a DSL event contract check, not an HTML5 tree builder. Void elements
 * must not receive content, optional end tags (e.g. `</li>`) are *not* inserted implicitly,
 * and unsafe blocks are forwarded without inspecting their raw payload.
 *
 * All state is per-instance; not wrapping a consumer costs nothing.
 */
class ValidatingTagConsumer<T>(
    val downstream: TagConsumer<T>,
    val mode: ValidationMode = ValidationMode.STRICT,
    val maxDiagnostics: Int = 32,
    /** Lightweight, lazily evaluated token identifying the DSL call source. Only invoked
     * when a diagnostic is created, so the happy path stays allocation-free. */
    val sourceToken: () -> String? = { null },
) : TagConsumer<T> {

    private val stack = ArrayList<OpenTagFrame>(16)
    private val collected = ArrayList<ValidationDiagnostic>()
    private var droppedDiagnostics = 0
    private var forwarded = 0L
    private var finalizeResult: Result<T>? = null

    /** Diagnostics recorded so far (bounded by [maxDiagnostics]). */
    val diagnostics: List<ValidationDiagnostic>
        get() = collected.toList()

    /** Number of diagnostics discarded because [maxDiagnostics] was reached. */
    val droppedDiagnosticCount: Int
        get() = droppedDiagnostics

    private fun report(kind: ValidationDiagnosticKind, event: String, message: String) {
        val diagnostic = diagnostic(kind, event, message)
        if (mode == ValidationMode.STRICT) {
            throw ValidationException(listOf(diagnostic))
        }
        record(diagnostic)
    }

    private fun diagnostic(kind: ValidationDiagnosticKind, event: String, message: String) =
        ValidationDiagnostic(
            kind = kind,
            event = event,
            message = message,
            tagStack = stack.map { it.name },
            sourceToken = sourceToken(),
            forwardedEvents = forwarded,
            finalized = finalizeResult != null,
        )

    private fun record(diagnostic: ValidationDiagnostic) {
        if (collected.size < maxDiagnostics) {
            collected.add(diagnostic)
        } else {
            droppedDiagnostics++
        }
    }

    private fun checkNotFinalized(event: String) {
        if (finalizeResult != null) {
            report(ValidationDiagnosticKind.EVENT_AFTER_FINALIZE, event, "event arrived after finalize() was invoked")
        }
    }

    private inline fun forward(event: String, crossinline validate: () -> Unit, crossinline action: () -> Unit) {
        checkNotFinalized(event)
        validate()
        action()
        forwarded++
    }

    override fun onTagStart(tag: Tag) {
        forward("onTagStart(${tag.tagName})", validate = {
            val seen = HashSet<String>()
            tag.attributesEntries.forEach { entry ->
                if (!seen.add(entry.key)) {
                    report(
                        ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE,
                        "onTagStart(${tag.tagName})",
                        "attribute '${entry.key}' appears more than once in attributesEntries"
                    )
                }
            }
        }) {
            downstream.onTagStart(tag)
            val names = HashSet<String>()
            tag.attributesEntries.forEach { names.add(it.key) }
            stack.add(OpenTagFrame(tag.tagName, tag.emptyTag, names))
        }
    }

    override fun onTagEnd(tag: Tag) {
        forward("onTagEnd(${tag.tagName})", validate = {
            val top = stack.lastOrNull()
            when {
                top == null -> report(
                    ValidationDiagnosticKind.END_TAG_WITHOUT_OPEN_TAG,
                    "onTagEnd(${tag.tagName})",
                    "end tag </${tag.tagName}> has no matching open tag"
                )

                top.name != tag.tagName -> report(
                    ValidationDiagnosticKind.MISMATCHED_END_TAG,
                    "onTagEnd(${tag.tagName})",
                    "expected </${top.name}> but got </${tag.tagName}>; no implicit repair is performed"
                )
            }
        }) {
            downstream.onTagEnd(tag)
            val top = stack.lastOrNull()
            if (top != null && top.name == tag.tagName) {
                stack.removeAt(stack.lastIndex)
            }
        }
    }

    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {
        forward("onTagAttributeChange(${tag.tagName}.$attribute)", validate = {
            val top = stack.lastOrNull()
            when {
                top == null -> report(
                    ValidationDiagnosticKind.CONTENT_OUTSIDE_TAG,
                    "onTagAttributeChange(${tag.tagName}.$attribute)",
                    "attribute change arrived while no tag is open"
                )

                top.contentSeen -> report(
                    ValidationDiagnosticKind.ATTRIBUTE_AFTER_CONTENT,
                    "onTagAttributeChange(${tag.tagName}.$attribute)",
                    "attribute '$attribute' changed after content was emitted inside <${top.name}>"
                )

                value != null && attribute in top.attributeNames -> report(
                    ValidationDiagnosticKind.DUPLICATE_ATTRIBUTE,
                    "onTagAttributeChange(${tag.tagName}.$attribute)",
                    "attribute '$attribute' is set again on <${top.name}>; the later value wins downstream"
                )
            }
        }) {
            downstream.onTagAttributeChange(tag, attribute, value)
            stack.lastOrNull()?.let { top ->
                if (value == null) top.attributeNames.remove(attribute) else top.attributeNames.add(attribute)
            }
        }
    }

    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {
        forward("onTagEvent(${tag.tagName}.$event)", validate = {}) {
            downstream.onTagEvent(tag, event, value)
        }
    }

    private fun contentEvent(event: String, action: () -> Unit) {
        forward(event, validate = {
            val top = stack.lastOrNull()
            when {
                top == null -> report(
                    ValidationDiagnosticKind.CONTENT_OUTSIDE_TAG,
                    event,
                    "content arrived while no tag is open"
                )

                top.isVoid -> report(
                    ValidationDiagnosticKind.CONTENT_IN_VOID_ELEMENT,
                    event,
                    "void element <${top.name}> cannot have content"
                )
            }
        }) {
            action()
            stack.lastOrNull()?.contentSeen = true
        }
    }

    override fun onTagContent(content: CharSequence) =
        contentEvent("onTagContent") { downstream.onTagContent(content) }

    override fun onTagContentEntity(entity: Entities) =
        contentEvent("onTagContentEntity(${entity.text})") { downstream.onTagContentEntity(entity) }

    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) =
        contentEvent("onTagContentUnsafe") { downstream.onTagContentUnsafe(block) }

    override fun onTagComment(content: CharSequence) =
        contentEvent("onTagComment") { downstream.onTagComment(content) }

    override fun finalize(): T {
        finalizeResult?.let { return it.getOrThrow() }

        if (stack.isNotEmpty()) {
            val diagnostic = diagnostic(
                ValidationDiagnosticKind.UNCLOSED_TAGS,
                "finalize()",
                "unclosed tag(s): ${stack.joinToString(", ") { "<${it.name}>" }}; " +
                    "optional end tags are not inserted implicitly"
            )
            if (mode == ValidationMode.STRICT) {
                // Idempotent: the cached failure is rethrown on every repeated call.
                val failure = Result.failure<T>(ValidationException(listOf(diagnostic)))
                finalizeResult = failure
                return failure.getOrThrow()
            }
            record(diagnostic)
        }

        // Downstream exceptions are propagated (and cached) as-is, never converted
        // into structural diagnostics.
        val result = try {
            Result.success(downstream.finalize())
        } catch (t: Throwable) {
            Result.failure(t)
        }
        finalizeResult = result
        return result.getOrThrow()
    }

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = downstream.head
}

/**
 * Wraps this consumer with structural validation. See [ValidatingTagConsumer] for semantics.
 */
fun <T> TagConsumer<T>.validate(
    mode: ValidationMode = ValidationMode.STRICT,
    maxDiagnostics: Int = 32,
    sourceToken: () -> String? = { null },
): ValidatingTagConsumer<T> = ValidatingTagConsumer(this, mode, maxDiagnostics, sourceToken)
