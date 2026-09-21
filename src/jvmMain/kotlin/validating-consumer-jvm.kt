package kotlinx.html.consumers

/**
 * A [ValidationConfig.sourceToken] implementation that captures the first stack
 * frame outside of [libraryPackage], i.e. the DSL call site that emitted the
 * offending event. The token is only computed when a diagnostic is created,
 * never on the happy path.
 */
fun jvmCallSiteSourceToken(libraryPackage: String = "kotlinx.html"): () -> Any? = {
    val prefix = "$libraryPackage."
    Throwable().stackTrace
        .firstOrNull { !it.className.startsWith(prefix) }
        ?.toString()
}
