package io.github.romanvht.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.databinding.ActivityMainBinding
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.system.exitProcess
import androidx.core.content.edit

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
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

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
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

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { log ->
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = log.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
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
            val (status, _) = appStatus

            if (status == AppStatus.Halted) {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        binding.editorButton.setOnClickListener {
            val useCmdSettings = getPreferences().getBoolean("byedpi_enable_cmd_settings", false)

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

        binding.domainListsButton.setOnClickListener {
            val intent = Intent(this, TestSettingsActivity::class.java)
            intent.putExtra("open_fragment", "domain_lists")
            startActivity(intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            requestBatteryOptimization()
        }

        if (getPreferences().getBoolean("auto_connect", false) && appStatus.first != AppStatus.Running) {
            this.start()
        }

        ShortcutUtils.update(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateButtonsVisibility()
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
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            R.id.action_close_app -> {
                if (status == AppStatus.Running) stop()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
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
        ServiceManager.stop(this)
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

    private fun updateTgWsStatus() {
        val prefs = getPreferences()
        val enabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        val (status, _) = appStatus

        val tv = binding.tgWsStatusText ?: return
        if (enabled && status == AppStatus.Running) {
            val tgStatus = prefs.getString(
                EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY,
                EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
            ) ?: EwenloyTgWsServiceExtension.TG_STATUS_DISABLED

            tv.text = when (tgStatus) {
                EwenloyTgWsServiceExtension.TG_STATUS_WS -> getString(R.string.tg_ws_main_ws)
                EwenloyTgWsServiceExtension.TG_STATUS_BYEDPI -> getString(R.string.tg_ws_main_byedpi)
                EwenloyTgWsServiceExtension.TG_STATUS_IDLE -> getString(R.string.tg_ws_main_idle)
                else -> getString(R.string.tg_ws_status_disabled)
            }
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun updateButtonsVisibility() {
        val useCmdSettings = getPreferences().getBoolean("byedpi_enable_cmd_settings", false)
        val visibility = if (useCmdSettings) View.VISIBLE else View.GONE
        binding.cmdButtonsRow.visibility = visibility
    }

    private fun requestBatteryOptimization() {
        val preferences = getPreferences()
        val alreadyRequested = preferences.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)

        if (!alreadyRequested && !BatteryUtils.isOptimizationDisabled(this)) {
            BatteryUtils.requestBatteryOptimization(this)
            preferences.edit { putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true) }
        }
    }
}