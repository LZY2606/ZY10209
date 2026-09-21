package kotlinx.html.tests

import kotlinx.html.Entities
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.TagConsumer
import kotlinx.html.consumers.HtmlValidationException
import kotlinx.html.consumers.ValidationMode
import kotlinx.html.consumers.validated

/**
 * A minimal [Tag] implementation used to drive raw, backend-agnostic event
 * scripts through a [kotlinx.html.consumers.ValidatingTagConsumer].
 */
class ScriptTag(
    override val tagName: String,
    override val consumer: TagConsumer<*>,
    override val emptyTag: Boolean = false,
    initialAttributes: Map<String, String> = emptyMap(),
) : Tag {
    override val namespace: String? = null
    override val attributes: MutableMap<String, String> = initialAttributes.toMutableMap()
    override val attributesEntries: Collection<Map.Entry<String, String>> get() = attributes.entries
    override val inlineTag: Boolean = true
}

class ContractRun(
    val summaries: List<String>,
    val downstreamFailures: List<String>,
    val downstreamFinalizeCalls: Int,
    val finalizeResult: Any?,
    val repeatedFinalizeResult: Any?,
)

/**
 * A deterministic event script shared by the validator contract tests.
 * The same script is executed against the JVM stream consumer, the JVM DOM
 * consumer and the JS/Wasm DOM consumers; every backend must observe exactly
 * [expectedSummaries]. Downstream consumers are allowed to fail on individual
 * events (e.g. the DOM builders reject mismatched end tags); those failures are
 * recorded in [ContractRun.downstreamFailures] and must never turn into
 * structural diagnostics.
 */
object ValidatorContract {

    @OptIn(ExperimentalKotlinxHtmlApi::class)
    private class FinalizeCounter<R>(private val downstream: TagConsumer<R>) : TagConsumer<R> by downstream {
        var finalizeCalls = 0
            private set

        override fun finalize(): R {
            finalizeCalls++
            return downstream.finalize()
        }
    }

    fun run(downstream: TagConsumer<*>): ContractRun {
        val counter = FinalizeCounter(downstream)
        val validator = counter.validated {
            mode = ValidationMode.COLLECT
            sourceToken = { "contract-script" }
        }

        val downstreamFailures = mutableListOf<String>()

        fun emit(label: String, event: () -> Unit) {
            try {
                event()
            } catch (e: HtmlValidationException) {
                throw AssertionError("COLLECT mode must not throw, but got: ${e.diagnostic}", e)
            } catch (t: Throwable) {
                downstreamFailures += label
            }
        }

        val div = ScriptTag("div", validator, initialAttributes = mapOf("id" to "a"))
        emit("start div") { validator.onTagStart(div) }
        emit("attr div.id") { validator.onTagAttributeChange(div, "id", "b") }
        emit("content hello") { validator.onTagContent("hello") }
        emit("entity nbsp") { validator.onTagContentEntity(Entities.nbsp) }
        emit("unsafe") { validator.onTagContentUnsafe { +"<b>raw</b>" } }
        emit("comment") { validator.onTagComment("note") }
        emit("attr div.class") { validator.onTagAttributeChange(div, "class", "x") }

        val br = ScriptTag("br", validator, emptyTag = true)
        emit("start br") { validator.onTagStart(br) }
        emit("content in br") { validator.onTagContent("void") }
        emit("end br") { validator.onTagEnd(br) }

        val ul = ScriptTag("ul", validator)
        val li1 = ScriptTag("li", validator)
        val li2 = ScriptTag("li", validator)
        emit("start ul") { validator.onTagStart(ul) }
        emit("start li 1") { validator.onTagStart(li1) }
        emit("start li 2") { validator.onTagStart(li2) }
        emit("end ul") { validator.onTagEnd(ul) }
        emit("end div") { validator.onTagEnd(div) }

        val p = ScriptTag("p", validator)
        emit("start p") { validator.onTagStart(p) }
        emit("end unknown") { validator.onTagEnd(ScriptTag("unknown", validator)) }

        val finalizeResult = runCatching { validator.finalize() }
        emit("content after finalize") { validator.onTagContent("late") }
        val repeatedFinalizeResult = runCatching { validator.finalize() }

        return ContractRun(
            summaries = validator.diagnostics.map { it.toString() },
            downstreamFailures = downstreamFailures,
            downstreamFinalizeCalls = counter.finalizeCalls,
            finalizeResult = finalizeResult.getOrNull(),
            repeatedFinalizeResult = repeatedFinalizeResult.getOrNull(),
        )
    }

    val expectedSummaries: List<String> = listOf(
        "ERROR DUPLICATE_ATTRIBUTE at onTagAttributeChange(div.id): attribute 'id' on <div> reassigned from 'a' to 'b'" +
                " | stack: div | output: forwarded=1 open=1 finalized=false | source: contract-script",
        "ERROR ATTRIBUTE_AFTER_CONTENT at onTagAttributeChange(div.class): attribute 'class' changed on <div> after content was emitted" +
                " | stack: div | output: forwarded=6 open=1 finalized=false | source: contract-script",
        "ERROR CONTENT_IN_VOID_ELEMENT at onTagContent: text content emitted inside void element <br>" +
                " | stack: div > br | output: forwarded=8 open=2 finalized=false | source: contract-script",
        "ERROR MISMATCHED_END_TAG at onTagEnd(ul): end tag </ul> does not match the innermost open tag <li>" +
                " | stack: div > ul > li > li | output: forwarded=13 open=4 finalized=false | source: contract-script",
        "WARNING UNCLOSED_OPTIONAL_END_TAG at onTagEnd(ul): optional end tag </li> omitted; implicitly closed by </ul>" +
                " | stack: div > ul > li > li | output: forwarded=13 open=4 finalized=false | source: contract-script",
        "WARNING UNCLOSED_OPTIONAL_END_TAG at onTagEnd(ul): optional end tag </li> omitted; implicitly closed by </ul>" +
                " | stack: div > ul > li > li | output: forwarded=13 open=4 finalized=false | source: contract-script",
        "ERROR END_TAG_WITHOUT_START at onTagEnd(unknown): end tag </unknown> has no matching start tag" +
                " | stack: p | output: forwarded=16 open=1 finalized=false | source: contract-script",
        "ERROR UNCLOSED_TAGS_AT_FINALIZE at finalize: finalize() called with unclosed tag(s): p" +
                " | stack: p | output: forwarded=17 open=1 finalized=false | source: contract-script",
        "ERROR EVENT_AFTER_FINALIZE at onTagContent: event received after finalize() was called" +
                " | stack: p | output: forwarded=17 open=1 finalized=true | source: contract-script",
    )
}
