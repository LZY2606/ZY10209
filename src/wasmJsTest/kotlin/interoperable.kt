import kotlinx.browser.document
import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.js.div
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalKotlinxHtmlApi::class)
class InteroperableImplTest {
    @Test fun testInteroperableDOM() {
        val wrapper = wrapper()

        wrapper.append.div {
            interop {
                document.createElement("svg")
            }
        }

        assertEquals(expected = "<div><svg></svg></div>", actual = wrapper.innerHTML)
    }

    private fun wrapper() = document.body!!.append.div {}

    // Stand in for how a library might want to interop with kotlinx-html
    private fun Tag.interop(block: () -> Node) {
        val last = consumer.head as HTMLElement
        last.appendChild(block())
    }
}