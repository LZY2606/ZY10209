[![Kotlin Stable](https://kotl.in/badges/stable.svg)](https://kotlinlang.org/docs/components-stability.html)
[![Official JetBrains Project](https://jb.gg/badges/official.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-green.svg?style=flat)](https://www.apache.org/licenses/LICENSE-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-blue.svg?logo=kotlin)](http://kotlinlang.org)

# kotlinx.html

The kotlinx.html library provides a DSL
to build HTML
to [Writer](https://docs.oracle.com/javase/8/docs/api/java/io/Writer.html)/[Appendable](https://docs.oracle.com/javase/8/docs/api/java/lang/Appendable.html)
or DOM.
Available to all Kotlin Multiplatform targets and browsers (or other WasmJS or JavaScript engines)
for better [Kotlin programming](https://kotlinlang.org) for Web.

# Get started

See [Getting started](https://github.com/kotlin/kotlinx.html/wiki/Getting-started) page for details how to include the
library.

# DOM

You can build a DOM tree with JVM, JS, and WASM.
The following example shows how to build the DOM for WasmJs-targeted Kotlin:

```kotlin
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.dom.create
import kotlinx.html.p

fun main() {
    val body = document.body ?: error("No body")
    body.append {
        div {
            p {
                +"Here is "
                a("https://kotlinlang.org") { +"official Kotlin site" }
            }
        }
    }

    val timeP = document.create.p {
        +"Time: 0"
    }

    body.append(timeP)

    var time = 0
    window.setInterval({
        time++
        timeP.textContent = "Time: $time"

        return@setInterval null
    }, 1000)
}
```

# Stream

You can build HTML directly to Writer (JVM) or Appendable (Multiplatform)

```kotlin
System.out.appendHTML().html {
    body {
        div {
            a("https://kotlinlang.org") {
                target = ATarget.blank
                +"Main site"
            }
        }
    }
}
```

# Validation

`TagConsumer.validated()` wraps any consumer (stream, DOM, or a composition with
`filter`/`onFinalize`/`trace`/`inject`) with structural validation, without
building a DOM. It checks tag nesting and end-tag pairing, duplicate attributes,
attribute changes arriving after content, content emitted into void elements,
and any writes after `finalize()`:

```kotlin
val consumer = StringBuilder()
    .appendHTML()
    .validated { mode = ValidationMode.COLLECT }
    .div { +"validated" }
```

Semantics:

- **Strict mode** (default) throws `HtmlValidationException` *before* a
  structure-breaking event is forwarded downstream.
- **Collect mode** (`ValidationMode.COLLECT`) is transparent: every event is
  forwarded in arrival order, and a bounded number of diagnostics
  (`ValidationConfig.maxDiagnostics`) is recorded in
  `ValidatingTagConsumer.diagnostics` instead.
- Each `HtmlValidationDiagnostic` carries the tag stack, the current event, an
  optional lightweight source token (`ValidationConfig.sourceToken`, evaluated
  lazily only when a diagnostic is created; `jvmCallSiteSourceToken()` on the
  JVM), and the output state at that moment.
- The validator reports what the DSL event stream actually does. Implicit fixes
  of the HTML tree builder are *not* treated as legal events: omitted optional
  end tags (e.g. `</li>`) are reported as warnings, mismatched end tags and
  content in void elements as errors.
- Diagnostics belong to the event stream at the wrapper's own position: with
  `stream.validated().filter { ... }` the validator sees the original events,
  with `stream.filter { ... }.validated()` it sees the transformed ones.
- `finalize()` is idempotent: downstream `finalize()` runs at most once, a
  downstream exception is rethrown as-is on every call, and downstream failures
  are never converted into structural diagnostics.

Complexity and compatibility:

- Validation is O(1) per event (O(depth) only for mismatched end tags) and
  O(depth) memory; recorded diagnostics are bounded by `maxDiagnostics`.
- Validation is opt-in per consumer instance and adds no global state, so the
  hot path of unwrapped consumers is unchanged.
- The wrapper only observes and forwards events; existing public behavior and
  all supported platforms are unaffected unless you opt in, and strict mode is
  the only mode that changes control flow (by throwing).

# Documentation

See [wiki](https://github.com/kotlin/kotlinx.html/wiki) pages

# Building

See the [development](https://github.com/kotlin/kotlinx.html/wiki/Development) page for details.
