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

# Documentation

See [wiki](https://github.com/kotlin/kotlinx.html/wiki) pages

# Validating consumer

`TagConsumer.validate()` (package `kotlinx.html.consumers`) wraps any consumer with
structural validation without building a DOM: tag nesting, end-tag pairing, duplicate
attributes, content placement and writes after `finalize()` are checked event by event.

```kotlin
val consumer = System.out.appendHTML().validate(mode = ValidationMode.STRICT)
consumer.div {
    +"validated"
}
consumer.finalize()
```

Semantics:

- **STRICT** (default): the first structural problem throws `ValidationException` *before*
  the offending event is forwarded, so the downstream consumer never observes a
  structure-breaking event.
- **COLLECT**: diagnostics are recorded (bounded by `maxDiagnostics`, the overflow is
  counted in `droppedDiagnosticCount`) and every event is still forwarded downstream
  unchanged. The validator does not resynchronize after a broken event, so one mistake
  may cause follow-up diagnostics.
- Each `ValidationDiagnostic` carries the event, a snapshot of the open tag stack, an
  optional caller-supplied `sourceToken` (evaluated lazily, only when a diagnostic is
  created), and the downstream output state (number of forwarded events, finalized flag).
- `finalize()` is idempotent: the downstream result (or exception) is cached and
  returned/rethrown on repeated calls. Downstream exceptions propagate as-is and are
  never converted into structural diagnostics.

This is a DSL event contract check, not an HTML5 tree builder: void elements must not
receive content, optional end tags (e.g. `</li>`) are not inserted implicitly, and
`unsafe { }` payloads are forwarded without inspection.

Composition: the validator only sees the events that reach it, so wrapper order decides
which stream is validated. `consumer.filter { ... }.validate()` validates the raw events,
while `consumer.validate().filter { ... }` validates the transformed events produced by
the filter; the same applies to `delayed`, `onFinalize`, `trace` and the injector.

Complexity: O(1) per event plus O(open tags) only when a diagnostic is created; memory is
O(open tags + recorded diagnostics). All state is per-instance — consumers that are not
wrapped pay nothing.

# Building

See the [development](https://github.com/kotlin/kotlinx.html/wiki/Development) page for details.
