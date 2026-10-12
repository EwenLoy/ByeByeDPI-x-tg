package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.StrategyResultAdapter
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.StrategyResult
import io.github.romanvht.byedpi.data.TestState
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.NativeProxy
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class TestActivity : BaseActivity() {

    private lateinit var strategiesRecyclerView: RecyclerView
    private lateinit var progressTextView: TextView
    private lateinit var disclaimerTextView: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var checkTgWsButton: MaterialButton
    private lateinit var strategyAdapter: StrategyResultAdapter
    private lateinit var cmdHistoryUtils: HistoryUtils

    private val strategies = mutableListOf<StrategyResult>()
    private var renderedRunId = -1L
    private val isTesting: Boolean get() = TestService.isRunning
    private val prefs by lazy { getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)
        setContentView(R.layout.activity_proxy_test)
        setupToolbar()

        cmdHistoryUtils = HistoryUtils(this)
        strategiesRecyclerView = findViewById(R.id.strategiesRecyclerView)
        startStopButton = findViewById(R.id.startStopButton)
        checkTgWsButton = findViewById(R.id.checkTgWsButton)
        progressTextView = findViewById(R.id.progressTextView)
        disclaimerTextView = findViewById(R.id.disclaimerTextView)

        strategyAdapter = StrategyResultAdapter(this,
            onApply = { command ->
                addToHistory(command)
            }
        )

        strategiesRecyclerView.layoutManager = LinearLayoutManager(this)
        strategiesRecyclerView.adapter = strategyAdapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TestService.state.collect { state ->
                    renderState(state)
                    if (!state.isLoaded && !state.isRunning) TestService.loadResults(applicationContext)
                }
            }
        }

        startStopButton.setOnClickListener {
            startStopButton.isClickable = false

            if (isTesting) TestService.stop() else TestService.start(this)

            startStopButton.postDelayed({ startStopButton.isClickable = true }, 1000)
        }

        startStopButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                startStopButton.strokeWidth = 10
                startStopButton.strokeColor = ColorStateList.valueOf(android.graphics.Color.argb(100, 0, 0, 0))
            } else {
                startStopButton.strokeWidth = 0
            }
        }

        checkTgWsButton.setOnClickListener {
            lifecycleScope.launch {
                checkTgWsButton.isEnabled = false
                val report = StringBuilder()

                // 1) Сервис TG WS запущен?
                val serviceRunning = TgWsProxyService.isRunning.value
                report.append(
                    getString(
                        if (serviceRunning) R.string.test_tg_ws_service_on
                        else R.string.test_tg_ws_service_off
                    )
                ).append('\n')

                // 2) Слушается ли локальный порт прокси?
                val tgPort = EwenloyTgWsServiceExtension.TG_WS_PORT
                val localOk = withContext(Dispatchers.IO) {
                    isHostReachable("127.0.0.1", tgPort, 3000)
                }
                report.append(
                    getString(
                        if (localOk) R.string.test_tg_ws_port_ok
                        else R.string.test_tg_ws_port_fail,
                        tgPort
                    )
                ).append('\n')

                // 3) Тип протокола на локальном порту: MTProto или SOCKS5?
                // Ядро — MTProto-прокси: ждёт 64-байтный obfuscated2-handshake.
                // Шлём SOCKS5-greeting (0x05 0x01 0x00):
                //   ответ 0x05       -> это SOCKS5-сервер (не то, что нужно)
                //   таймаут/закрытие -> MTProto listener (ожидаемо)
                val protoResult = if (localOk) withContext(Dispatchers.IO) {
                    detectProxyProtocol("127.0.0.1", tgPort)
                } else PROTO_UNKNOWN
                report.append(
                    when (protoResult) {
                        PROTO_MTPROTO -> getString(R.string.test_tg_ws_proto_ok, tgPort)
                        PROTO_SOCKS5 -> getString(R.string.test_tg_ws_proto_socks, tgPort)
                        else -> getString(R.string.test_tg_ws_proto_unknown, tgPort)
                    }
                ).append('\n')

                // 4) Живое ли нативное ядро (.so) и корректный ли секрет?
                val coreOk = withContext(Dispatchers.IO) {
                    try {
                        val s = NativeProxy.getSecretWithPrefix()
                        if (s != null && s.startsWith("dd") && s.length == 34) {
                            report.append(getString(R.string.test_tg_ws_secret_ok, s.take(10)))
                                .append('\n')
                            true
                        } else {
                            report.append(getString(R.string.test_tg_ws_secret_fail, "bad secret"))
                                .append('\n')
                            false
                        }
                    } catch (t: Throwable) {
                        report.append(
                            getString(
                                R.string.test_tg_ws_secret_fail,
                                t.message ?: t.javaClass.simpleName
                            )
                        ).append('\n')
                        false
                    }
                }

                // 5) Доступен ли удалённый WS-сервер Telegram (без прокси)?
                val host = "kws1.web.telegram.org"
                val remoteOk = withContext(Dispatchers.IO) {
                    isHostReachable(host, 443, 4000)
                }
                report.append(
                    getString(
                        if (remoteOk) R.string.test_tg_ws_remote_ok
                        else R.string.test_tg_ws_remote_fail,
                        host
                    )
                )

                progressTextView.text = report.toString()
                val summary = if (serviceRunning && localOk && protoResult == PROTO_MTPROTO && coreOk) {
                    getString(R.string.tg_ws_main_ws)
                } else {
                    getString(R.string.test_tg_ws_service_off)
                }
                Toast.makeText(this@TestActivity, summary, Toast.LENGTH_SHORT).show()
                checkTgWsButton.isEnabled = true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isTesting && appStatus.first == AppStatus.Running) {
                    val intent = Intent(this@TestActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                }
                finish()
            }
        })

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_test, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy_log -> {
                copyLog()
                true
            }
            R.id.action_settings -> {
                if (!isTesting) {
                    val intent = Intent(this, TestSettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
                }
                true
            }
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun renderState(state: TestState) {
        startStopButton.text = getString(if (state.isRunning) R.string.test_stop else R.string.test_start)
        startStopButton.isEnabled = !state.isStopping
        if (state.isRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        progressTextView.text = when {
            state.isRunning && state.currentStrategy > 0 -> getString(R.string.test_process, state.currentStrategy, state.strategies.size)
            state.isRunning -> ""
            state.failed || state.wasInterrupted -> getString(R.string.test_proxy_error)
            state.strategies.isNotEmpty() -> getString(R.string.test_complete)
            else -> ""
        }
        disclaimerTextView.visibility = if (state.wasInterrupted || (!state.isRunning && state.strategies.isEmpty())) View.VISIBLE else View.GONE
        disclaimerTextView.text = getString(if (state.wasInterrupted) R.string.test_crash else R.string.test_disclaimer)

        strategyAdapter.setTestingState(state.isRunning)
        val replace = renderedRunId != state.runId || strategies.map { it.command } != state.strategies.map { it.command }
        val reorder = replace || strategies.indices.any { strategies[it].isCompleted != state.strategies[it].isCompleted }
        if (replace) {
            strategies.clear()
            strategies.addAll(state.strategies.map { it.copy(siteResults = it.siteResults.toMutableList()) })
            renderedRunId = state.runId
        } else {
            strategies.forEachIndexed { index, strategy ->
                val value = state.strategies[index]
                if (strategy.copy(isExpanded = value.isExpanded) != value) {
                    strategy.successCount = value.successCount
                    strategy.totalRequests = value.totalRequests
                    strategy.currentProgress = value.currentProgress
                    strategy.isCompleted = value.isCompleted
                    strategy.siteResults.clear()
                    strategy.siteResults.addAll(value.siteResults)
                    if (!reorder) strategyAdapter.updateStrategy(strategy)
                }
            }
        }
        if (reorder) strategyAdapter.updateStrategies(strategies)
    }

    private fun addToHistory(command: String) {
        if (isTesting) return

        prefs.edit(commit = true) { putString("byedpi_cmd_args", command) }
        lifecycleScope.launch(Dispatchers.IO) {
            cmdHistoryUtils.addCommand(command)

            val mode = prefs.mode()
            if (mode == Mode.VPN && VpnService.prepare(this@TestActivity) != null) return@launch

            val toastText = withContext(Dispatchers.Main) {
                if (!isTesting && appStatus.first == AppStatus.Running) {
                    ServiceManager.restart(this@TestActivity, mode)
                    R.string.service_restart
                } else {
                    R.string.cmd_history_applied
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@TestActivity, toastText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyLog() {
        val completeStrategies = strategies.filter { it.isCompleted }

        if (completeStrategies.isEmpty()) {
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()

        completeStrategies.forEach { strategy ->
            sb.appendLine("${strategy.command}\n")

            strategy.siteResults.forEach { site ->
                sb.appendLine("${site.site} - ${site.successCount}/${site.totalCount}")
            }

            sb.appendLine("\n${strategy.successCount}/${strategy.totalRequests}")
            sb.appendLine("-------------")
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("proxy_test_log", sb.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PROTO_MTPROTO = 0
        const val PROTO_SOCKS5 = 1
        const val PROTO_UNKNOWN = 2
    }

    /**
     * Определяет тип прокси на локальном порту.
     * MTProto-прокси (tgwsproxy) ждёт 64-байтный obfuscated2-handshake и молчит
     * на чужие данные. SOCKS5-сервер ответил бы 0x05 на greeting 0x05 0x01 0x00.
     */
    private fun detectProxyProtocol(host: String, port: Int): Int {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.soTimeout = 2500
                val out = socket.getOutputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00)) // SOCKS5 greeting
                out.flush()
                when (val first = socket.getInputStream().read()) {
                    0x05 -> PROTO_SOCKS5          // ответил как SOCKS5 — не то
                    -1 -> PROTO_MTPROTO           // сразу закрыл — поведение MTProto
                    else -> PROTO_UNKNOWN         // неизвестный ответ
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            PROTO_MTPROTO // молчит, ждёт 64-байтный handshake — это MTProto
        } catch (_: Exception) {
            PROTO_UNKNOWN // соединение сброшено или иная ошибка
        }
    }

    private fun isHostReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
