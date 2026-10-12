package io.github.romanvht.byedpi.ewenloy.tgws

// Copied from tg-ws-proxy-android (amurcanov) MainActivity.kt
// Only package changed, @Immutable removed (no Compose)

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

object LogManager {
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)
    private val logChannel = Channel<LogEntry>(capacity = BUFFERED)

    fun startListening() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            val readerJob = launch(Dispatchers.IO) {
                try {
                    val pid = android.os.Process.myPid()
                    val process = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                        .redirectErrorStream(true)
                        .start()
                    logcatProcess = process
                    process.inputStream.bufferedReader().use { reader ->
                        while (isActive) {
                            val line = try { reader.readLine() } catch (e: Exception) { null } ?: break
                            val entry = parseLine(line) ?: continue
                            logChannel.trySend(entry)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    logcatProcess?.destroy()
                    logcatProcess = null
                }
            }
            launch {
                val pendingBatch = mutableListOf<LogEntry>()
                while (isActive) {
                    var received = logChannel.tryReceive()
                    while (received.isSuccess) {
                        pendingBatch.add(received.getOrThrow())
                        if (pendingBatch.size >= 20) break
                        received = logChannel.tryReceive()
                    }
                    if (pendingBatch.isNotEmpty()) {
                        logs.value = applyBatch(logs.value, pendingBatch)
                        pendingBatch.clear()
                    }
                    delay(150)
                }
            }
            readerJob.join()
        }
    }

    private fun applyBatch(current: List<LogEntry>, batch: List<LogEntry>): List<LogEntry> {
        val result = ArrayDeque(current)
        for (entry in batch) {
            var merged = false
            val searchDepth = minOf(result.size, 10)
            for (i in result.indices.reversed().take(searchDepth)) {
                if (result[i].message == entry.message) {
                    val existing = result.removeAt(i)
                    result.addLast(existing.copy(count = existing.count + 1))
                    merged = true
                    break
                }
            }
            if (!merged) result.addLast(entry)
        }
        while (result.size > 50) result.removeFirst()
        return result.toList()
    }

    fun stopListening() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    private fun parseLine(raw: String): LogEntry? {
        var message: String
        val isError: Boolean
        val priority: Int
        when {
            raw.contains("[ERROR]") -> {
                message = raw.substringAfter("[ERROR]").trim()
                isError = true
                priority = 6
            }
            raw.contains("[WARN]") -> {
                message = raw.substringAfter("[WARN]").trim()
                isError = false
                priority = 5
            }
            raw.contains("[DEBUG]") -> {
                message = raw.substringAfter("[DEBUG]").trim()
                isError = false
                priority = 3
            }
            raw.contains("TgWsProxy") -> {
                var msg = raw.substringAfter("TgWsProxy:").trim()
                if (msg.startsWith("[ERROR]") || msg.startsWith("[WARN]") || msg.startsWith("[DEBUG]")) return null
                if (msg.contains("↑")) msg = msg.substringBefore("↑").trim()
                if (msg.contains("↓")) msg = msg.substringBefore("↓").trim()
                message = msg
                isError = false
                priority = 4
            }
            else -> return null
        }
        val emojiRegex = Regex("[\\x{1F300}-\\x{1F5FF}\\x{1F900}-\\x{1F9FF}\\x{1F600}-\\x{1F64F}\\x{1F680}-\\x{1F6FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}⚠✅❌⚡🔥🔄🔗]")
        message = message.replace(emojiRegex, "").trim()
        val isEssential = listOf("pool", "key:", "started", "address:", "error", "failed", "blocked")
            .any { message.contains(it, ignoreCase = true) }
        return LogEntry(
            key = "log_${nextKey.getAndIncrement()}",
            message = message,
            count = 1,
            isError = isError,
            priority = priority,
            isEssential = isEssential
        )
    }
}
