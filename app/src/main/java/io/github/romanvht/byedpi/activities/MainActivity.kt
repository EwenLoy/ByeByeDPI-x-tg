package io.github.romanvht.byedpi.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.databinding.ActivityMainBinding
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var historyUtils: HistoryUtils
    private val tgWsExt = EwenloyTgWsServiceExtension()
    private val tgWsStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tgWsStatusUpdater = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) return
            updateTgWsStatus()
            tgWsStatusHandler.postDelayed(this, 1500)
        }
    }

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName
        private const val BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested"

        private fun collectLogs(): String? {
            return try {
                val process = Runtime.getRuntime().exec("logcat *:D -d")
                process.inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to collect logs", exception)
                null
            }
        }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::handleLogsFileResult
    )

    private fun handleLogsFileResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val data = result.data ?: return
        val uri = data.data
        val path = data.getStringExtra(FileActivity.EXTRA_PATH)
        val file = if (path == null) null else File(path)
        if (uri == null && file == null) return

        lifecycleScope.launch(Dispatchers.IO) {
            val logs = collectLogs()
            if (logs == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val outputStream = when {
                    uri != null -> contentResolver.openOutputStream(uri)
                    file != null -> file.outputStream()
                    else -> null
                }
                if (outputStream == null) {
                    Log.e(TAG, "Failed to open output stream")
                } else {
                    outputStream.use { stream ->
                        stream.write(logs.toByteArray())
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save logs", exception)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()

        tgWsExt.initialize(this, getPreferences())

        historyUtils = HistoryUtils(this)

        tgWsExt.initialize(this, getPreferences())

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.statusButtonCard.setOnClickListener {
            binding.statusButtonCard.isClickable = false

            try {
                val (status, _) = appStatus
                when (status) {
                    AppStatus.Halted -> start()
                    AppStatus.Running -> stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Button click error", e)
            }

            binding.statusButtonCard.postDelayed({
                binding.statusButtonCard.isClickable = true
            }, 3000)
        }

        binding.statusButtonCard.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.statusButtonCard.strokeWidth = 10
                binding.statusButtonCard.strokeColor = android.graphics.Color.argb(100, 0, 0, 0)
            } else {
                binding.statusButtonCard.strokeWidth = 0
            }
        }

        binding.settingsButton.setOnClickListener {
            if (TestService.isRunning) {
                Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (appStatus.first == AppStatus.Running) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.editorButton.setOnClickListener {
            val useCmdSettings = getPreferences().getCmdEnable()

            if (!useCmdSettings && appStatus.first == AppStatus.Running) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("open_fragment", if (useCmdSettings) "cmd" else "ui")
            startActivity(intent)
        }

        binding.testProxyButton.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }

        // Тап по статус-строке TG WS — открыть логи прокси
        binding.tgWsStatusText?.setOnClickListener {
            startActivity(Intent(this, TgWsLogsActivity::class.java))
        }

        binding.tgProxyButton?.setOnClickListener {
            val prefs = getPreferences()
            val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
            if (tgWsEnabled) {
                val port = EwenloyTgWsServiceExtension.TG_WS_PORT
                val secretKey = prefs.getString(EwenloyTgWsServiceExtension.PREF_SECRET_KEY, "") ?: ""
                val secret = if (secretKey.isEmpty()) "00000000000000000000000000000000" else secretKey
                val url = "https://t.me/proxy?server=127.0.0.1&port=$port&secret=dd$secret"
                applyToTelegramPackages(url)
            } else {
                val port = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
                val tgUri = android.net.Uri.parse("tg://socks?server=127.0.0.1&port=$port")
                startActivity(Intent(Intent.ACTION_VIEW, tgUri))
            }
        }

        binding.tgWsToggleButton?.setOnClickListener {
            val prefs = getPreferences()
            val current = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
            prefs.edit().putBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, !current).apply()
            updateTgWsToggleButton()
            // Независимое управление: TG WS прокси стартует/останавливается сам,
            // независимо от состояния основного ByeDPI-сервиса
            tgWsExt.initialize(this, prefs)
            if (!current) {
                tgWsExt.start(prefs)
                Toast.makeText(this, R.string.tg_ws_toggle_btn, Toast.LENGTH_SHORT).show()
            } else {
                tgWsExt.stop()
            }
            // Если основной сервис запущен — перезапускаем, чтобы он обновил
            // маршрутизацию и статус в своём уведомлении
            if (appStatus.first == AppStatus.Running) {
                ServiceManager.restart(this, getPreferences().mode())
                Toast.makeText(this, R.string.service_restart, Toast.LENGTH_SHORT).show()
            }
        }

        binding.domainListsButton.setOnClickListener {
            val intent = Intent(this, TestSettingsActivity::class.java)
            intent.putExtra("open_fragment", "domain_lists")
            startActivity(intent)
        }

        // Тап по статус-строке TG WS — открыть логи прокси
        binding.tgWsStatusText.setOnClickListener {
            startActivity(Intent(this, TgWsLogsActivity::class.java))
        }

        binding.tgProxyButton.setOnClickListener {
            val prefs = getPreferences()
            val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
            if (tgWsEnabled) {
                val port = EwenloyTgWsServiceExtension.TG_WS_PORT
                val secretKey = prefs.getString(EwenloyTgWsServiceExtension.PREF_SECRET_KEY, "") ?: ""
                val secret = if (secretKey.isEmpty()) "00000000000000000000000000000000" else secretKey
                val url = "https://t.me/proxy?server=127.0.0.1&port=$port&secret=dd$secret"
                applyToTelegramPackages(url)
            } else {
                val port = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
                val tgUri = android.net.Uri.parse("tg://socks?server=127.0.0.1&port=$port")
                startActivity(Intent(Intent.ACTION_VIEW, tgUri))
            }
        }

        binding.tgWsToggleButton.setOnClickListener {
            val prefs = getPreferences()
            val current = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
            prefs.edit().putBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, !current).apply()
            updateTgWsToggleButton()
            // Независимое управление: TG WS прокси стартует/останавливается сам,
            // независимо от состояния основного ByeDPI-сервиса
            tgWsExt.initialize(this, prefs)
            if (!current) {
                tgWsExt.start(prefs)
                Toast.makeText(this, R.string.tg_ws_toggle_btn, Toast.LENGTH_SHORT).show()
            } else {
                tgWsExt.stop()
            }
        }

        binding.strategyButton.setOnClickListener {
            showStrategyPicker()
        }

        if (!PermissionUtils.hasNotificationPermission(this)) {
            PermissionUtils.requestNotificationPermission(this, 1)
        } else {
            requestBatteryOptimization()
        }

        if (!TestService.isRunning && getPreferences().getBoolean("auto_connect", false) &&
            appStatus.first != AppStatus.Running) {
            this.start()
        }

        ShortcutUtils.update(this)
    }

    override fun onResume() {
        super.onResume()
        ServiceManager.refresh(this)
        updateStatus()
        updateStrategyButton()
        updateTgWsToggleButton()
        tgWsStatusHandler.post(tgWsStatusUpdater)
    }

    override fun onPause() {
        super.onPause()
        tgWsStatusHandler.removeCallbacks(tgWsStatusUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        tgWsStatusHandler.removeCallbacks(tgWsStatusUpdater)
        unregisterReceiver(receiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) {
            requestBatteryOptimization()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_diagnostics -> {
                showDiagnostics()
                true
            }

            R.id.action_save_logs -> {
                val intent = Intent(this, FileActivity::class.java)
                intent.putExtra(FileActivity.EXTRA_MODE, FileActivity.MODE_CREATE)
                intent.putExtra(FileActivity.EXTRA_TYPE, FileActivity.TYPE_LOGS)
                logsRegister.launch(intent)
                true
            }

            R.id.action_tg_ws_logs -> {
                startActivity(Intent(this, TgWsLogsActivity::class.java))
                true
            }

            R.id.action_tg_ws_logs -> {
                startActivity(Intent(this, TgWsLogsActivity::class.java))
                true
            }

            R.id.action_close_app -> {
                TestService.stop()
                lifecycleScope.launch {
                    try {
                        TestService.awaitStopped()
                        ServiceManager.waitStop()
                    } catch (exception: Exception) {
                        Log.e(TAG, "Failed to stop service before closing", exception)
                        return@launch
                    }
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDiagnostics() {
        val report = DiagnosticUtils.buildReport(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val textView = TextView(this).apply {
            text = report
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setView(scrollView)
            .setPositiveButton(R.string.diagnostic_copy) { _, _ ->
                ClipboardUtils.copy(this, report, getString(R.string.diagnostics))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            copyButton.nextFocusLeftId = cancelButton.id
            copyButton.nextFocusRightId = cancelButton.id
            cancelButton.nextFocusLeftId = copyButton.id
            cancelButton.nextFocusRightId = copyButton.id
            copyButton.requestFocus()
        }
        dialog.show()
    }

    private fun start() {
        if (TestService.isRunning) {
            Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop()
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val (ip, port) = preferences.getProxyIpAndPort()

        binding.proxyAddress.text = getString(R.string.proxy_address, ip, port)

        when (status) {
            AppStatus.Halted -> {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.colorPrimary, typedValue,true)
                binding.statusButtonCard.setCardBackgroundColor(typedValue.data)
                binding.statusButtonIcon.clearColorFilter()

                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                    }
                }
            }

            AppStatus.Running -> {
                binding.statusButtonCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.green_active))
                binding.statusButtonIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.white))

                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                    }
                }
            }
        }
    }

    private fun updateStrategyButton() {
        val useCmdSettings = getPreferences().getCmdEnable()

        if (!useCmdSettings) {
            binding.cmdButtonsRow.visibility = View.GONE
            binding.strategyButton.visibility = View.GONE
            return
        }

        binding.cmdButtonsRow.visibility = View.VISIBLE

        val pinned = historyUtils.getPinnedHistory()
        val currentCmdArgs = getPreferences().getCmdArgs()

        val matched = pinned.find { it.text == currentCmdArgs }
        val name = matched?.name?.takeIf { it.isNotBlank() }

        if (name != null) {
            binding.strategyButtonName.text = name
            binding.strategyButtonName.visibility = View.VISIBLE
            binding.strategyButtonText.maxLines = 1
        } else {
            binding.strategyButtonName.visibility = View.GONE
            binding.strategyButtonText.maxLines = 2
        }

        binding.strategyButtonText.text = currentCmdArgs.ifBlank { getString(R.string.main_strategy_picker) }
        binding.strategyButton.visibility = View.VISIBLE
    }

    private fun showStrategyPicker() {
        val pinned = historyUtils.getPinnedHistory()

        if (pinned.isEmpty()) {
            Toast.makeText(this, R.string.main_strategy_no_pinned, Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = object : ArrayAdapter<Command>(
            this,
            R.layout.item_main_strategy,
            pinned
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_main_strategy,
                    parent,
                    false
                )

                val command = getItem(position)!!

                val nameView = view.findViewById<TextView>(R.id.strategyName)
                val textView = view.findViewById<TextView>(R.id.strategyText)
                val dividerView = view.findViewById<View>(R.id.strategyDivider)

                val name = command.name?.takeIf { it.isNotBlank() }

                if (name != null) {
                    nameView.visibility = View.VISIBLE
                    nameView.text = name
                    textView.maxLines = 2
                } else {
                    nameView.visibility = View.GONE
                    textView.maxLines = 3
                }

                textView.text = command.text
                dividerView.visibility = if (position == count - 1) View.GONE else View.VISIBLE

                return view
            }
        }

        val listView = ListView(this).apply {
            divider = null
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            this.adapter = adapter
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_strategy_picker))
            .setView(listView)
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            applyStrategy(pinned[position].text)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyStrategy(commandText: String) {
        getPreferences().edit { putString("byedpi_cmd_args", commandText) }

        updateStrategyButton()

        if (appStatus.first == AppStatus.Running) {
            val mode = getPreferences().mode()
            if (mode == Mode.VPN && VpnService.prepare(this) != null) {
                return
            }

            ServiceManager.restart(this, mode)
            Toast.makeText(this, R.string.service_restart, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimization() {
        val preferences = getPreferences()
        val alreadyRequested = preferences.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)

        if (!alreadyRequested && !PermissionUtils.isBatteryOptimizationDisabled(this)) {
            PermissionUtils.requestBatteryOptimization(this)
            preferences.edit { putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true) }
        }
    }

    private fun updateTgWsStatus() {
        val prefs = getPreferences()
        val enabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)

        val tv = binding.tgWsStatusText
        if (enabled) {
            // TG WS прокси работает независимо от основного сервиса
            if (!TgWsProxyService.isRunning.value &&
                prefs.getString(
                    EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY,
                    EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
                ) == EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
            ) {
                tv.text = getString(R.string.tg_ws_main_idle)
            } else {
                val tgStatus = prefs.getString(
                    EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY,
                    EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
                ) ?: EwenloyTgWsServiceExtension.TG_STATUS_DISABLED

                tv.text = when (tgStatus) {
                    EwenloyTgWsServiceExtension.TG_STATUS_WS -> getString(R.string.tg_ws_main_ws)
                    EwenloyTgWsServiceExtension.TG_STATUS_DIRECT -> getString(R.string.tg_ws_main_direct)
                    EwenloyTgWsServiceExtension.TG_STATUS_IDLE -> getString(R.string.tg_ws_main_idle)
                    else -> getString(R.string.tg_ws_main_idle)
                }
            }
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun updateTgWsToggleButton() {
        val enabled = getPreferences().getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        val btn = binding.tgWsToggleButton
        val icon = binding.tgWsToggleIcon
        if (enabled) {
            btn.setCardBackgroundColor(ContextCompat.getColor(this, R.color.green_active))
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.white))
        } else {
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
            btn.setCardBackgroundColor(typedValue.data)
            icon.clearColorFilter()
        }
    }

    private fun applyToTelegramPackages(url: String) {
        val packages = listOf(
            "org.telegram.messenger", "com.radolyn.ayugram", "com.exteragram.messenger",
            "org.telegram.plus", "ir.ilmili.telegraph", "org.telegram.BifToGram",
            "tw.nekomimi.nekogram", "xyz.nextalone.nagram", "uz.unnarsx.cherrygram",
            "org.telegram.mdgram", "org.forkclient.messenger.beta", "app.nicegram",
            "top.qwq2333.nullgram", "com.iMe.android", "ru.dahl.messenger",
            "com.scriptsaz.litegram", "org.thunderdog.challegram"
        )
        val pm = packageManager
        val available = packages.filter {
            pm.getLaunchIntentForPackage(it) != null
        }
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
            return
        }
        val intents = available.map { pkg ->
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply { setPackage(pkg) }
        }
        if (intents.size == 1) {
            startActivity(intents.first().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val chooser = Intent.createChooser(intents.first(), getString(R.string.ewenloy_tg_choose_client))
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
            startActivity(chooser)
        }
    }
}
