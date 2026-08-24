package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.StrategyResultAdapter
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.SiteResult
import io.github.romanvht.byedpi.data.StrategyResult
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.NativeProxy
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.SiteCheckUtils
import io.github.romanvht.byedpi.utility.getIntStringNotNull
import io.github.romanvht.byedpi.utility.getLongStringNotNull
import androidx.core.content.edit
import io.github.romanvht.byedpi.utility.getStringNotNull
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.utility.DomainListUtils
import io.github.romanvht.byedpi.utility.mode
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class TestActivity : BaseActivity() {

    private lateinit var strategiesRecyclerView: RecyclerView
    private lateinit var progressTextView: TextView
    private lateinit var disclaimerTextView: TextView
    private lateinit var startStopButton: Button
    private lateinit var checkTgWsButton: Button
    private lateinit var strategyAdapter: StrategyResultAdapter

    private lateinit var siteChecker: SiteCheckUtils
    private lateinit var cmdHistoryUtils: HistoryUtils
    private lateinit var sites: List<String>
    private lateinit var cmds: List<String>

    private var savedCmd: String = ""
    private var testJob: Job? = null
    private val strategies = mutableListOf<StrategyResult>()
    private val gson = Gson()

    private var isTesting: Boolean
        get() = prefs.getBoolean("is_test_running", false)
        set(value) {
            prefs.edit(commit = true) { putBoolean("is_test_running", value) }
        }

    private val prefs by lazy { getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_test)

        val ip = prefs.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val port = prefs.getIntStringNotNull("byedpi_proxy_port", 1080)

        siteChecker = SiteCheckUtils(ip, port)
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
            val previousResults = loadResults()

            if (previousResults.isNotEmpty()) {
                progressTextView.text = getString(R.string.test_complete)
                disclaimerTextView.visibility = View.GONE

                strategies.clear()
                strategies.addAll(previousResults)

                strategyAdapter.updateStrategies(strategies)
            }

            if (isTesting) {
                progressTextView.text = getString(R.string.test_proxy_error)
                disclaimerTextView.text = getString(R.string.test_crash)
                disclaimerTextView.visibility = View.VISIBLE
                isTesting = false
            }
        }

        startStopButton.setOnClickListener {
            startStopButton.isClickable = false

            if (isTesting) {
                stopTesting()
            } else {
                startTesting()
            }

            startStopButton.postDelayed({ startStopButton.isClickable = true }, 1000)
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

                // 3) Доступен ли удалённый WS-сервер Telegram (без прокси)?
                // 3) Тип протокола на локальном порту: MTProto или SOCKS5?
                // Ядро — MTProto-прокси: ждёт 64-байтный obfuscated2-handshake.
                // Шлём SOCKS5-greeting (\x05\x01\x00):
                //   ответ \x05       -> это SOCKS5-сервер (не то, что нужно)
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
                if (isTesting) {
                    stopTesting()
                } else {
                    if (appStatus.first == AppStatus.Running) {
                        val intent = Intent(this@TestActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        startActivity(intent)
                    }
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
            R.id.action_settings -> {
                if (!isTesting) {
                    val intent = Intent(this, TestSettingsActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
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

    private suspend fun waitForProxyStatus(statusNeeded: AppStatus): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 3000) {
            if (appStatus.first == statusNeeded) {
                delay(500)
                return true
            }
            delay(100)
        }
        return false
    }

    private suspend fun isProxyRunning(): Boolean = withContext(Dispatchers.IO) {
        appStatus.first == AppStatus.Running
    }

    private fun updateCmdArgs(cmd: String) {
        prefs.edit(commit = true) { putString("byedpi_cmd_args", cmd) }
    }

    private fun startTesting() {
        sites = loadSites()
        cmds = loadCmds()

        if (sites.isEmpty()) {
            Toast.makeText(this, R.string.test_settings_domain_empty, Toast.LENGTH_LONG).show()
            return
        }

        testJob = lifecycleScope.launch(Dispatchers.IO) {
            isTesting = true
            savedCmd = prefs.getString("byedpi_cmd_args", "").orEmpty()

            strategies.clear()
            strategies.addAll(cmds.map { StrategyResult(command = it) })

            withContext(Dispatchers.Main) {
                disclaimerTextView.visibility = View.GONE

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startStopButton.text = getString(R.string.test_stop)
                progressTextView.text = ""

                strategyAdapter.setTestingState(true)
                strategyAdapter.updateStrategies(strategies, sortByPercentage = false)
            }

            if (isProxyRunning()) {
                ServiceManager.stop(this@TestActivity)
                waitForProxyStatus(AppStatus.Halted)
            }

            val delaySec = prefs.getIntStringNotNull("byedpi_proxytest_delay", 1)
            val requestsCount = prefs.getIntStringNotNull("byedpi_proxytest_requests", 1)
            val requestTimeout = prefs.getLongStringNotNull("byedpi_proxytest_timeout", 5)
            val requestLimit = prefs.getIntStringNotNull("byedpi_proxytest_limit", 20)

            for (strategyIndex in strategies.indices) {
                if (!isActive) break

                val strategy = strategies[strategyIndex]
                val cmdIndex = strategyIndex + 1

                withContext(Dispatchers.Main) {
                    progressTextView.text = getString(R.string.test_process, cmdIndex, cmds.size)
                }

                updateCmdArgs(strategy.command)

                if (isProxyRunning()) stopTesting()
                else ServiceManager.start(this@TestActivity, Mode.Proxy)

                if (!waitForProxyStatus(AppStatus.Running)) {
                    stopTesting()
                }

                delay(delaySec * 500L)

                val totalRequests = sites.size * requestsCount
                strategy.totalRequests = totalRequests

                withContext(Dispatchers.Main) {
                    strategyAdapter.notifyItemChanged(strategyIndex)
                }

                siteChecker.checkSitesAsync(
                    sites = sites,
                    requestsCount = requestsCount,
                    requestTimeout = requestTimeout,
                    concurrentRequests = requestLimit,
                    fullLog = true,
                    onSiteChecked = { site, successCount, countRequests ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            strategy.currentProgress += countRequests
                            strategy.successCount += successCount
                            strategy.siteResults.add(SiteResult(site, successCount, countRequests))

                            strategyAdapter.notifyItemChanged(strategyIndex, "progress")
                        }
                    }
                )

                strategy.isCompleted = true

                withContext(Dispatchers.Main) {
                    strategyAdapter.updateStrategies(strategies, sortByPercentage = true)
                    saveResults(strategies)
                }

                if (isProxyRunning()) ServiceManager.stop(this@TestActivity)
                else stopTesting()

                if (!waitForProxyStatus(AppStatus.Halted)) {
                    stopTesting()
                }

                delay(delaySec * 500L)
            }

            stopTesting()
        }
    }

    private fun stopTesting() {
        if (!isTesting) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            isTesting = false
            updateCmdArgs(savedCmd)

            testJob?.cancel()
            testJob = null

            if (isProxyRunning()) {
                ServiceManager.stop(this@TestActivity)
            }

            withContext(Dispatchers.Main) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                startStopButton.text = getString(R.string.test_start)
                progressTextView.text = getString(R.string.test_complete)

                strategyAdapter.setTestingState(false)
                strategyAdapter.updateStrategies(strategies, sortByPercentage = true)

                saveResults(strategies)
            }
        }
    }

    private fun addToHistory(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            updateCmdArgs(command)
            cmdHistoryUtils.addCommand(command)

            val mode = prefs.mode()
            if (mode == Mode.VPN && VpnService.prepare(this@TestActivity) != null) return@launch

            val toastText = if (appStatus.first == AppStatus.Running) {
                ServiceManager.restart(this@TestActivity, mode)
                R.string.service_restart
            } else {
                R.string.cmd_history_applied
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@TestActivity, toastText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveResults(results: List<StrategyResult>) {
        val file = File(filesDir, "proxy_test_results.json")
        val json = gson.toJson(results)
        file.writeText(json)
    }

    private fun loadResults(): List<StrategyResult> {
        val file = File(filesDir, "proxy_test_results.json")
        return if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<StrategyResult>>() {}.type
                gson.fromJson<List<StrategyResult>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    private fun loadSites(): List<String> {
        DomainListUtils.initializeDefaultLists(this)
        return DomainListUtils.getActiveDomains(this)
    }

    private fun loadCmds(): List<String> {
        val userCommands = prefs.getBoolean("byedpi_proxytest_usercommands", false)
        val sniValue = prefs.getStringNotNull("byedpi_proxytest_sni", "google.com")

        return if (userCommands) {
            val content = prefs.getStringNotNull("byedpi_proxytest_commands", "")
            content.replace("{sni}", sniValue).lines().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            val content = assets.open("proxytest_strategies.list").bufferedReader().readText()
            content.replace("{sni}", sniValue).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private companion object {
        const val PROTO_MTPROTO = 0
        const val PROTO_SOCKS5 = 1
        const val PROTO_UNKNOWN = 2
    }

    /**
     * Определяет тип прокси на локальном порту.
     * MTProto-прокси (tgwsproxy) ждёт 64-байтный obfuscated2-handshake и молчит
     * на чужие данные. SOCKS5-сервер ответил бы 0x05 на greeting \x05\x01\x00.
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
