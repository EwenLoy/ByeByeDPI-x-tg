package io.github.romanvht.byedpi.ewenloy.tgws

// Copied from tg-ws-proxy-android (amurcanov) MainActivity.kt
// Only package changed, @Immutable removed (no Compose)

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object LogManager {
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    private var job: Job? = null
    private var logcatProcess: Process? = null
    private val nextKey = AtomicLong(0)
    private val logChannel = Channel<LogEntry>(capacity = BUFFERED)
    private var logFile: File? = null
    private var logWriter: BufferedWriter? = null
    private var appContext: Context? = null

    private const val LOG_FILE_NAME = "tgws-runtime.log"
    private const val LOG_FILE_PREV = "tgws-runtime.1.log"
    private const val LOG_FILE_MAX_BYTES = 256 * 1024L
    private const val MAX_MEMORY_ENTRIES = 300

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Событие уровня приложения. Пишется в файл всегда — не зависит от того,
     * доступен ли logcat на устройстве, и переживает смерть процесса.
     */
    fun app(message: String, error: Boolean = false) {
        ensureLogFile()
        val entry = LogEntry(
            key = "app_${nextKey.getAndIncrement()}",
            message = message,
            count = 1,
            isError = error,
            priority = if (error) 6 else 4,
            isEssential = error
        )
        appendToFile(entry)
        logs.value = (logs.value + entry).takeLast(MAX_MEMORY_ENTRIES)
    }

    fun startListening(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        ensureLogFile()
        if (job?.isActive == true) return

        job = CoroutineScope(Dispatchers.IO).launch {
            val readerJob = launch(Dispatchers.IO) {
                try {
                    // Без --pid: logcat отдаёт строки всех процессов приложения,
                    // включая :native, где живёт нативное ядро.
                    val process = ProcessBuilder("logcat", "-v", "tag")
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
                    // Suspend until a line arrives: no periodic wakeups while idle
                    val first = try {
                        logChannel.receive()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        break
                    } catch (_: Exception) {
                        continue
                    }
                    pendingBatch.add(first)
                    while (pendingBatch.size < 20) {
                        val next = logChannel.tryReceive()
                        if (next.isSuccess) pendingBatch.add(next.getOrThrow()) else break
                    }
                    logs.value = applyBatch(logs.value, pendingBatch)
                    for (entry in pendingBatch) {
                        appendToFile(entry)
                    }
                    pendingBatch.clear()
                }
            }
            readerJob.join()
        }
    }

    /**
     * Открывает файл лога. При превышении лимита текущий файл уезжает в
     * tgws-runtime.1.log, поэтому история предыдущей сессии не теряется.
     */
    private fun ensureLogFile() {
        val ctx = appContext ?: return
        val file = logFile ?: File(ctx.filesDir, LOG_FILE_NAME).also { logFile = it }
        if (logWriter != null) return
        try {
            if (file.exists() && file.length() > LOG_FILE_MAX_BYTES) {
                val previous = File(ctx.filesDir, LOG_FILE_PREV)
                previous.delete()
                file.renameTo(previous)
            }
            logWriter = java.io.FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("LogManager", "Failed to open log file", e)
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
        while (result.size > MAX_MEMORY_ENTRIES) result.removeFirst()
        return result.toList()
    }

    fun stopListening() {
        job?.cancel()
        job = null
        logcatProcess?.destroy()
        logcatProcess = null
        try {
            logWriter?.close()
        } catch (_: Exception) {
        }
        logWriter = null
    }

    fun clearLogs() {
        logs.value = emptyList()
        // Закрываем поток перед обрезкой: иначе после truncate курсор остаётся
        // за концом файла и в лог попадает дыра из нулевых байтов.
        try {
            logWriter?.close()
        } catch (_: Exception) {
        }
        logWriter = null
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
        ensureLogFile()
    }

    fun getLogFile(context: Context? = null): File? {
        if (logFile == null && context != null) {
            logFile = File(context.filesDir, LOG_FILE_NAME)
        }
        return logFile
    }

    fun readLogFile(context: Context? = null): String {
        return try {
            val file = getLogFile(context)
            if (file?.exists() == true) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Аварийный вариант: показать хотя бы предыдущую сессию из ротации. */
    fun readPreviousLogFile(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_PREV)
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun appendToFile(entry: LogEntry) {
        val writer = logWriter ?: return
        try {
            val ts = timeFormat.get()?.format(Date()) ?: ""
            val prefix = if (entry.isError) "[ERR] " else ""
            writer.write("$ts $prefix${entry.message}\n")
            writer.flush()
        } catch (_: Exception) {
        }
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
                // Формат -v tag: "I/TgWsProxy( 1234): текст".
                var msg = raw.substringAfter("TgWsProxy").trimStart(':', ' ')
                msg = msg.replace(Regex("^\\(\\s*\\d+\\):\\s*"), "")
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
