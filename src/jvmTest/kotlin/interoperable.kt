import kotlinx.html.ExperimentalKotlinxHtmlApi
import kotlinx.html.Tag
import kotlinx.html.div
import kotlinx.html.dom.createHTMLDocument
import kotlinx.html.dom.serialize
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalKotlinxHtmlApi::class)
class InteroperableImplTest {
    @Test fun testInteroperableDOM() {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val tree = createHTMLDocument().div {
            interop {
                it.createElement("svg")
            }
        }
        assertEquals(expected = "<!DOCTYPE html>\n<div><svg></svg></div>", actual = tree.serialize(prettyPrint = false))
    }

    // Stand in for how a library might want to interop with kotlinx-html
    private fun Tag.interop(block: (Document) -> Node) {
        val last = consumer.head as Element
        last.appendChild(block(last.ownerDocument))
    }
}