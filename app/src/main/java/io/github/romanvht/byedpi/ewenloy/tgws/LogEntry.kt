package io.github.romanvht.byedpi.ewenloy.tgws

/**
 * Immutable data class for log entries.
 * Copied from tg-ws-proxy-android (amurcanov). @Immutable removed — no Compose in ByeDPI.
 */
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int,
    val isError: Boolean,
    val priority: Int, // 3=DEBUG, 4=INFO, 5=WARN, 6=ERROR
    val isEssential: Boolean = false
)
