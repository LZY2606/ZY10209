package kotlinx.html.consumers

import kotlinx.html.Entities
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.Unsafe
import kotlinx.html.org.w3c.dom.events.Event

/**
 * How tags whose end tag is optional according to the HTML5 specification.
 * Omitting them is tolerated by browser tree builders, but at the DSL event level
 * the validator reports them as [ValidationKind.UNCLOSED_OPTIONAL_END_TAG] warnings
 * instead of silently applying the spec's implicit fixes.
 */
private val OPTIONAL_END_TAGS: Set<String> = setOf(
    "html", "head", "body", "p", "li", "dt", "dd", "rt", "rp",
    "optgroup", "option", "colgroup", "caption", "thead", "tbody", "tfoot", "tr", "td", "th"
)

/**
 * How a [ValidatingTagConsumer] reacts to structural errors.
 */
enum class ValidationMode {
    /** Throws [HtmlValidationException] before forwarding the first structure-breaking event. */
    STRICT,

    /** Records a bounded number of diagnostics and stays transparent: every event is forwarded in arrival order. */
    COLLECT,
}

enum class ValidationSeverity {
    WARNING,
    ERROR,
}

/**
 * The kinds of structural problems detected at the DSL event level.
 * The validator deliberately does not replicate the implicit fixes of the HTML
 * tree builder: it reports what the event stream actually does.
 */
enum class ValidationKind {
    /** An end tag arrived while no tag was open, or no matching start tag exists on the stack. */
    END_TAG_WITHOUT_START,

    /** An end tag matches an open tag that is not the innermost one; the tags in between are left unclosed. */
    MISMATCHED_END_TAG,

    /** A tag with an optional end tag (e.g. `li`, `p`, `td`) was implicitly closed by a mismatched end tag. */
    UNCLOSED_OPTIONAL_END_TAG,

    /** `finalize()` was called while tags were still open. */
    UNCLOSED_TAGS_AT_FINALIZE,

    /** The same attribute was assigned twice on the same open tag. */
    DUPLICATE_ATTRIBUTE,

    /** An attribute change arrived after content was emitted for the open tag. */
    ATTRIBUTE_AFTER_CONTENT,

    /** An attribute change targeted a tag that is not currently open. */
    ATTRIBUTE_FOR_UNKNOWN_TAG,

    /** Text, entity, unsafe or comment content was emitted directly inside a void element. */
    CONTENT_IN_VOID_ELEMENT,

    /** Any event arrived after `finalize()`. */
    EVENT_AFTER_FINALIZE,
}

/**
 * A snapshot of what the validating consumer had already emitted downstream
 * at the moment a diagnostic was raised.
 */
class ValidationOutputState(
    val eventsForwarded: Long,
    val openTags: Int,
    val finalized: Boolean,
) {
    override fun toString(): String = "forwarded=$eventsForwarded open=$openTags finalized=$finalized"
}

/**
 * A single structural problem found in the event stream, with enough context
 * to locate it: the tag stack, the current event, an optional lightweight
 * source token and the output state at that moment.
 */
class HtmlValidationDiagnostic(
    val kind: ValidationKind,
    val severity: ValidationSeverity,
    val event: String,
    val message: String,
    val tagStack: List<String>,
    val sourceToken: String?,
    val outputState: ValidationOutputState,
) {
    override fun toString(): String = buildString {
        append(severity).append(' ').append(kind)
        append(" at ").append(event).append(": ").append(message)
        append(" | stack: ").append(if (tagStack.isEmpty()) "<empty>" else tagStack.joinToString(" > "))
        append(" | output: ").append(outputState)
        if (sourceToken != null) {
            append(" | source: ").append(sourceToken)
        }
    }
}

/**
 * Thrown by [ValidatingTagConsumer] in [ValidationMode.STRICT] before a
 * structure-breaking event would be forwarded downstream.
 */
class HtmlValidationException(val diagnostic: HtmlValidationDiagnostic) : IllegalStateException(diagnostic.toString())

/**
 * Configuration of a [ValidatingTagConsumer]. All state lives in the consumer
 * instance itself; not wrapping a consumer keeps the hot path free of any
 * validation-related (global) state.
 */
class ValidationConfig {
    /** Strict by default; use [ValidationMode.COLLECT] to only record diagnostics. */
    var mode: ValidationMode = ValidationMode.STRICT

    /** Maximum number of diagnostics kept in [ValidatingTagConsumer.diagnostics]; further ones are only counted. */
    var maxDiagnostics: Int = 64

    /**
     * Optional lightweight token identifying the DSL call site, evaluated lazily
     * only when a diagnostic is created (never on the happy path).
     * On the JVM, [jvmCallSiteSourceToken] provides a stack-based implementation.
     */
    var sourceToken: (() -> Any?)? = null

    /** Invoked for every diagnostic, in both modes, before a possible strict-mode throw. */
    var onDiagnostic: (HtmlValidationDiagnostic) -> Unit = {}

    /** Kinds that should be neither recorded nor thrown. */
    val ignoredKinds: MutableSet<ValidationKind> = mutableSetOf()
}

/**
 * A composable [TagConsumer] wrapper that validates the structural well-formedness
 * of the event stream without building a DOM: tag nesting, end tag pairing,
 * duplicate attributes and writes after `finalize()`.
 *
 * The wrapper preserves the downstream event order. In [ValidationMode.STRICT] it
 * fails before forwarding a structure-breaking event; in [ValidationMode.COLLECT]
 * it is fully transparent and records a bounded number of diagnostics instead.
 *
 * Diagnostics always describe the event stream as seen at the wrapper's own
 * position: when combined with transforming wrappers such as [filter], diagnostics
 * raised upstream of the filter refer to the original events, while diagnostics
 * raised downstream of it refer to the transformed events.
 */
class ValidatingTagConsumer<R>(
    val downstream: TagConsumer<R>,
    val config: ValidationConfig = ValidationConfig(),
) : TagConsumer<R> {

    private class OpenTag(
        val tag: Tag,
        val name: String,
        val isVoid: Boolean,
        val hasOptionalEnd: Boolean,
        val attributes: MutableMap<String, String>,
        var hasContent: Boolean,
    )

    private val stack = ArrayList<OpenTag>()
    private val recordedDiagnostics = ArrayList<HtmlValidationDiagnostic>()
    private var eventsForwarded = 0L
    private var finalized = false
    private var finalizeOutcome: Result<R>? = null

    /** Diagnostics recorded so far (bounded by [ValidationConfig.maxDiagnostics]). */
    val diagnostics: List<HtmlValidationDiagnostic>
        get() = recordedDiagnostics.toList()

    /** Number of diagnostics that were not recorded because [ValidationConfig.maxDiagnostics] was reached. */
    var droppedDiagnosticCount: Int = 0
        private set

    override fun onTagStart(tag: Tag) {
        val event = "onTagStart(${tag.tagName})"
        checkFinalized(event)
        checkVoidContent(event, "start tag <${tag.tagName}>")

        val attributes = LinkedHashMap<String, String>()
        tag.attributesEntries.forEach { attributes[it.key] = it.value }
        stack.add(OpenTag(tag, tag.tagName, tag.emptyTag, tag.tagName in OPTIONAL_END_TAGS, attributes, false))

        forward { downstream.onTagStart(tag) }
    }

    override fun onTagEnd(tag: Tag) {
        val event = "onTagEnd(${tag.tagName})"
        checkFinalized(event)

        when {
            stack.isEmpty() -> report(
                ValidationKind.END_TAG_WITHOUT_START, ValidationSeverity.ERROR, event,
                "end tag </${tag.tagName}> has no matching start tag"
            )

            stack.last().name == tag.tagName -> stack.removeAt(stack.lastIndex)

            else -> {
                val matchIndex = stack.indexOfLast { it.name == tag.tagName }
                if (matchIndex < 0) {
                    report(
                        ValidationKind.END_TAG_WITHOUT_START, ValidationSeverity.ERROR, event,
                        "end tag </${tag.tagName}> has no matching start tag"
                    )
                } else {
                    val implied = stack.subList(matchIndex + 1, stack.size).toList()
                    report(
                        ValidationKind.MISMATCHED_END_TAG, ValidationSeverity.ERROR, event,
                        "end tag </${tag.tagName}> does not match the innermost open tag <${stack.last().name}>"
                    )
                    implied.forEach { unclosed ->
                        if (unclosed.hasOptionalEnd) {
                            report(
                                ValidationKind.UNCLOSED_OPTIONAL_END_TAG, ValidationSeverity.WARNING, event,
                                "optional end tag </${unclosed.name}> omitted; implicitly closed by </${tag.tagName}>"
                            )
                        }
                    }
                    repeat(stack.size - matchIndex) { stack.removeAt(stack.lastIndex) }
                }
            }
        }

        forward { downstream.onTagEnd(tag) }
    }

    override fun onTagAttributeChange(tag: Tag, attribute: String, value: String?) {
        val event = "onTagAttributeChange(${tag.tagName}.$attribute)"
        checkFinalized(event)

        var target = stack.lastOrNull { it.tag === tag }
        if (target == null && stack.lastOrNull()?.name == tag.tagName) {
            target = stack.last()
        }

        when {
            target == null -> report(
                ValidationKind.ATTRIBUTE_FOR_UNKNOWN_TAG, ValidationSeverity.ERROR, event,
                "attribute '$attribute' changed on <${tag.tagName}> which is not an open tag"
            )

            else -> {
                if (target !== stack.lastOrNull()) {
                    report(
                        ValidationKind.ATTRIBUTE_FOR_UNKNOWN_TAG, ValidationSeverity.ERROR, event,
                        "attribute '$attribute' changed on <${tag.tagName}> which is not the innermost open tag"
                    )
                }
                if (target.hasContent) {
                    report(
                        ValidationKind.ATTRIBUTE_AFTER_CONTENT, ValidationSeverity.ERROR, event,
                        "attribute '$attribute' changed on <${tag.tagName}> after content was emitted"
                    )
                }
                if (value != null) {
                    val previous = target.attributes.put(attribute, value)
                    if (previous != null) {
                        report(
                            ValidationKind.DUPLICATE_ATTRIBUTE, ValidationSeverity.ERROR, event,
                            "attribute '$attribute' on <${tag.tagName}> reassigned from '$previous' to '$value'"
                        )
                    }
                } else {
                    target.attributes.remove(attribute)
                }
            }
        }

        forward { downstream.onTagAttributeChange(tag, attribute, value) }
    }

    override fun onTagEvent(tag: Tag, event: String, value: (Event) -> Unit) {
        checkFinalized("onTagEvent(${tag.tagName}.$event)")
        forward { downstream.onTagEvent(tag, event, value) }
    }

    override fun onTagContent(content: CharSequence) {
        val event = "onTagContent"
        checkFinalized(event)
        checkVoidContent(event, "text content")
        stack.lastOrNull()?.hasContent = true
        forward { downstream.onTagContent(content) }
    }

    override fun onTagContentEntity(entity: Entities) {
        val event = "onTagContentEntity"
        checkFinalized(event)
        checkVoidContent(event, "entity content")
        stack.lastOrNull()?.hasContent = true
        forward { downstream.onTagContentEntity(entity) }
    }

    override fun onTagContentUnsafe(block: Unsafe.() -> Unit) {
        val event = "onTagContentUnsafe"
        checkFinalized(event)
        checkVoidContent(event, "unsafe content")
        stack.lastOrNull()?.hasContent = true
        forward { downstream.onTagContentUnsafe(block) }
    }

    override fun onTagComment(content: CharSequence) {
        val event = "onTagComment"
        checkFinalized(event)
        checkVoidContent(event, "comment")
        stack.lastOrNull()?.hasContent = true
        forward { downstream.onTagComment(content) }
    }

    /**
     * Idempotent: the downstream `finalize()` is invoked at most once. Repeated
     * calls replay the recorded outcome, including rethrowing a downstream
     * exception as-is; downstream failures are never converted into structural
     * diagnostics.
     */
    override fun finalize(): R {
        finalizeOutcome?.let { return it.getOrThrow() }

        if (!finalized) {
            if (stack.isNotEmpty()) {
                report(
                    ValidationKind.UNCLOSED_TAGS_AT_FINALIZE, ValidationSeverity.ERROR, "finalize",
                    "finalize() called with unclosed tag(s): ${stack.joinToString(", ") { it.name }}"
                )
            }
            finalized = true
        }

        val outcome = try {
            Result.success(downstream.finalize())
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
        finalizeOutcome = outcome
        return outcome.getOrThrow()
    }

    @ExperimentalKotlinxHtmlApi
    override val head: Any?
        get() = downstream.head

    private fun checkFinalized(event: String) {
        if (finalized) {
            report(
                ValidationKind.EVENT_AFTER_FINALIZE, ValidationSeverity.ERROR, event,
                "event received after finalize() was called"
            )
        }
    }

    private fun checkVoidContent(event: String, what: String) {
        val top = stack.lastOrNull()
        if (top != null && top.isVoid) {
            report(
                ValidationKind.CONTENT_IN_VOID_ELEMENT, ValidationSeverity.ERROR, event,
                "$what emitted inside void element <${top.name}>"
            )
        }
    }

    private inline fun forward(block: () -> Unit) {
        eventsForwarded++
        block()
    }

    private fun report(kind: ValidationKind, severity: ValidationSeverity, event: String, message: String) {
        if (kind in config.ignoredKinds) return

        val diagnostic = HtmlValidationDiagnostic(
            kind = kind,
            severity = severity,
            event = event,
            message = message,
            tagStack = stack.map { it.name },
            sourceToken = config.sourceToken?.invoke()?.toString(),
            outputState = ValidationOutputState(eventsForwarded, stack.size, finalized),
        )

        config.onDiagnostic(diagnostic)
        if (recordedDiagnostics.size < config.maxDiagnostics) {
            recordedDiagnostics.add(diagnostic)
        } else {
            droppedDiagnosticCount++
        }

        if (config.mode == ValidationMode.STRICT && severity == ValidationSeverity.ERROR) {
            throw HtmlValidationException(diagnostic)
        }
    }
}

/**
 * Wraps this consumer with structural validation; see [ValidatingTagConsumer].
 * Validation adds no global state: consumers that are not wrapped keep their
 * original hot path.
 */
fun <R> TagConsumer<R>.validated(configure: ValidationConfig.() -> Unit = {}): ValidatingTagConsumer<R> =
    ValidatingTagConsumer(this, ValidationConfig().apply(configure))
