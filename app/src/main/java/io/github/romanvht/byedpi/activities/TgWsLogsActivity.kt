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
            val text = formatLogs()
            if (text.isBlank()) return@setOnClickListener
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
        LogManager.startListening()
        logsTextView.removeCallbacks(updateRunnable)
        logsTextView.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        logsTextView.removeCallbacks(updateRunnable)
    }

    private fun formatLogs(): String =
        LogManager.logs.value.joinToString("\n") { entry ->
            buildString {
                if (entry.isError) append("[!] ")
                append(entry.message)
                if (entry.count > 1) append("  x").append(entry.count)
            }
        }

    private fun renderLogs() {
        val entries = LogManager.logs.value
        logsTextView.text = if (entries.isEmpty()) {
            getString(R.string.tg_ws_logs_empty)
        } else {
            formatLogs()
        }
    }
}
