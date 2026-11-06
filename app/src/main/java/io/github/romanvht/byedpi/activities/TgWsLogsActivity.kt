package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.ewenloy.tgws.LogManager

/**
 * Экран логов TG WS прокси.
 * Источник — LogManager: читает logcat процесса и отбирает строки
 * нативного ядра (тег TgWsProxy) и маркеры [ERROR]/[WARN]/[DEBUG].
 */
class TgWsLogsActivity : BaseActivity() {

    private lateinit var logsTextView: TextView
    private var cachedFileLength = -1L
    private var cachedFileText = ""

    companion object {
        /** Больше в TextView не показываем: файл отдаём целиком только при «Поделиться». */
        private const val MAX_DISPLAY_LINES = 400
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            renderLogs()
            logsTextView.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tg_ws_logs)

        logsTextView = findViewById(R.id.logsTextView)

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            LogManager.clearLogs()
            renderLogs()
        }

        findViewById<Button>(R.id.btnShareLogs).setOnClickListener {
            // Раньше кнопка молча ничего не делала на пустом логе — теперь шлём файл,
            // а если и файла нет, то хотя бы предыдущую сессию или пояснение.
            val text = shareText()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.tg_ws_logs_title))
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.tg_ws_logs_share)))
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        // Идемпотентно: если сервис уже слушает logcat — ничего не меняет
        LogManager.startListening(this)
        logsTextView.removeCallbacks(updateRunnable)
        logsTextView.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        logsTextView.removeCallbacks(updateRunnable)
    }

    /** События приложения из файла; натив приходит отдельным блоком. */
    private fun appLogText(): String {
        val file = LogManager.getLogFile(this)
        val length = file?.length() ?: -1L
        if (length >= 0) {
            // Перечитываем файл только когда он вырос: экран обновляется чаще, чем пишется лог
            if (length != cachedFileLength) {
                cachedFileLength = length
                cachedFileText = LogManager.readLogFile(this)
            }
            if (cachedFileText.isNotBlank()) {
                val lines = cachedFileText.trimEnd().lines()
                return if (lines.size > MAX_DISPLAY_LINES) {
                    lines.takeLast(MAX_DISPLAY_LINES).joinToString("\n")
                } else {
                    lines.joinToString("\n")
                }
            }
        }
        val memoryLogs = LogManager.logs.value
        if (memoryLogs.isNotEmpty()) {
            return memoryLogs.joinToString("\n") { entry ->
                buildString {
                    if (entry.isError) append("[!] ")
                    append(entry.message)
                    if (entry.count > 1) append("  x").append(entry.count)
                }
            }
        }
        return ""
    }

    private fun formatLogs(): String = buildString {
        append(appLogText())
        val native = LogManager.readNativeLog(this@TgWsLogsActivity, MAX_DISPLAY_LINES)
        if (native.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("=== ядро (tgws-native.log) ===\n").append(native)
        }
    }

    private fun shareText(): String = buildString {
        val app = LogManager.readLogFile(this@TgWsLogsActivity).ifBlank {
            LogManager.readPreviousLogFile(this@TgWsLogsActivity)
        }
        append(app)
        val native = LogManager.readNativeLog(this@TgWsLogsActivity, maxLines = 5000)
        if (native.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("=== ядро (tgws-native.log) ===\n").append(native)
        }
        if (isEmpty()) append(getString(R.string.tg_ws_logs_empty))
    }

    private fun renderLogs() {
        val text = formatLogs()
        logsTextView.text = if (text.isBlank()) {
            getString(R.string.tg_ws_logs_empty)
        } else {
            text
        }
    }
}
