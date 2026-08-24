package io.github.romanvht.byedpi.fragments

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.activities.TestActivity
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateTelegramRuntimeStatus()
            uiHandler.postDelayed(this, 1500)
        }
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("byedpi_proxy_ip") { checkIp(it) }
        setEditTestPreferenceListenerPort("byedpi_proxy_port")
        setEditTextPreferenceListener("dns_ip") { it.isBlank() || checkNotLocalIp(it) }

        findPreferenceNotNull<ListPreference>("language")
            .setOnPreferenceChangeListener { _, newValue ->
                SettingsUtils.setLang(newValue as String)
                true
            }

        findPreferenceNotNull<ListPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                SettingsUtils.setTheme(newValue as String)
                true
            }

        findPreferenceNotNull<Preference>("proxy_test")
            .setOnPreferenceClickListener {
                val intent = Intent(context, TestActivity::class.java)
                startActivity(intent)
                true
            }

        findPreferenceNotNull<Preference>("ewenloy_tg_open_proxy_link")
            .setOnPreferenceClickListener {
                val prefs = sharedPreferences ?: return@setOnPreferenceClickListener true
                val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
                
                if (tgWsEnabled) {
                    // MTProto WS Proxy — используем tg://proxy?...&secret=
                    val port = EwenloyTgWsServiceExtension.TG_WS_PORT
                    val secretKey = prefs.getString(EwenloyTgWsServiceExtension.EWENLOY_TG_SECRET_KEY, "") ?: ""
                    val secretForUrl = if (secretKey.isEmpty()) "00000000000000000000000000000000" else secretKey
                    val proxyUrl = "https://t.me/proxy?server=127.0.0.1&port=$port&secret=dd$secretForUrl"
                    applyToTelegramPackages(requireContext(), proxyUrl)
                } else {
                    // SOCKS5 режим
                    val port = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
                    val tgUri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, tgUri))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }

        findPreferenceNotNull<Preference>("ewenloy_tg_diagnostics")
            .setOnPreferenceClickListener {
                showDiagnosticsDialog()
                true
            }

        findPreferenceNotNull<SwitchPreference>("ewenloy_tg_ws_mode_enabled")
            .setOnPreferenceChangeListener { _, _ ->
                uiHandler.postDelayed({ updateProxyLinkSummary() }, 100)
                if (appStatus.first == AppStatus.Running) {
                    val mode = sharedPreferences?.mode() ?: Mode.VPN
                    ServiceManager.restart(requireContext(), mode)
                    Toast.makeText(requireContext(), R.string.service_restart, Toast.LENGTH_SHORT).show()
                }
                true
            }

        findPreferenceNotNull<SwitchPreference>("tg_ws_cf_enabled")
            .setOnPreferenceChangeListener { _, _ ->
                if (appStatus.first == AppStatus.Running) {
                    val mode = sharedPreferences?.mode() ?: Mode.VPN
                    ServiceManager.restart(requireContext(), mode)
                    Toast.makeText(requireContext(), R.string.service_restart, Toast.LENGTH_SHORT).show()
                }
                true
            }

        findPreferenceNotNull<ListPreference>("tg_ws_pool_size")
            .setOnPreferenceChangeListener { _, _ ->
                if (appStatus.first == AppStatus.Running) {
                    val mode = sharedPreferences?.mode() ?: Mode.VPN
                    ServiceManager.restart(requireContext(), mode)
                    Toast.makeText(requireContext(), R.string.service_restart, Toast.LENGTH_SHORT).show()
                }
                true
            }

        findPreferenceNotNull<Preference>("tg_ws_secret_key")
            .setOnPreferenceClickListener {
                showSecretKeyDialog()
                true
            }

        findPreferenceNotNull<Preference>("battery_optimization")
            .setOnPreferenceClickListener {
                BatteryUtils.requestBatteryOptimization(requireContext())
                true
            }

        findPreferenceNotNull<Preference>("storage_access")
            .setOnPreferenceClickListener {
                StorageUtils.requestStoragePermission(this)
                true
            }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME
        findPreferenceNotNull<Preference>("byedpi_version").summary = "0.17.3"

        updatePreferences()
        updateProxyLinkSummary()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.post(statusUpdater)
        updatePreferences()
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.removeCallbacks(statusUpdater)
    }

    private fun updateProxyLinkSummary() {
        val prefs = sharedPreferences ?: return
        val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        val linkPref = findPreferenceNotNull<Preference>("ewenloy_tg_open_proxy_link")
        if (tgWsEnabled) {
            linkPref.summary = getString(R.string.ewenloy_tg_open_proxy_summary_ws)
        } else {
            linkPref.summary = getString(R.string.ewenloy_tg_open_proxy_summary)
        }
    }

    private fun updatePreferences() {
        val cmdEnable = findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings").isChecked
        val mode = findPreferenceNotNull<ListPreference>("byedpi_mode").value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val ipv6 = findPreferenceNotNull<SwitchPreference>("ipv6_enable")
        val proxy = findPreferenceNotNull<PreferenceCategory>("byedpi_proxy_category")

        val applistType = findPreferenceNotNull<ListPreference>("applist_type")
        val selectedApps = findPreferenceNotNull<Preference>("selected_apps")
        val batteryOptimization = findPreferenceNotNull<Preference>("battery_optimization")
        val storageAccess = findPreferenceNotNull<Preference>("storage_access")

        val uiSettings = findPreferenceNotNull<Preference>("byedpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("byedpi_cmd_settings")
        val proxyTest = findPreferenceNotNull<Preference>("proxy_test")

        if (cmdEnable) {
            val (cmdIp, cmdPort) = sharedPreferences?.checkIpAndPortInCmd() ?: Pair(null, null)
            proxy.isVisible = cmdIp == null && cmdPort == null
        } else {
            proxy.isVisible = true
        }

        uiSettings.isEnabled = !cmdEnable
        cmdSettings.isEnabled = cmdEnable
        proxyTest.isEnabled = cmdEnable

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = true
                ipv6.isVisible = true

                when (applistType.value) {
                    "disable" -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = false
                    }
                    "blacklist", "whitelist" -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = true
                    }
                    else -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = false
                        Log.w(TAG, "Unexpected applistType value: ${applistType.value}")
                    }
                }
            }

            Mode.Proxy -> {
                dns.isVisible = false
                ipv6.isVisible = false
                applistType.isVisible = false
                selectedApps.isVisible = false
            }
        }

        if (BatteryUtils.isOptimizationDisabled(requireContext())) {
            batteryOptimization.summary = getString(R.string.battery_optimization_disabled_summary)
        } else {
            batteryOptimization.summary = getString(R.string.battery_optimization_summary)
        }

        if (StorageUtils.hasStoragePermission(requireContext())) {
            storageAccess.summary = getString(R.string.storage_access_allowed_summary)
        } else {
            storageAccess.summary = getString(R.string.storage_access_summary)
        }

        updateTelegramRuntimeStatus()
    }

    private fun updateTelegramRuntimeStatus() {
        val prefs = sharedPreferences ?: return
        val statusPref = findPreferenceNotNull<Preference>("ewenloy_tg_runtime_status")
        val enabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        val status = prefs.getString(
            EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY,
            EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
        ) ?: EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
        val summaryRes = when {
            !enabled -> R.string.tg_ws_status_disabled
            status == EwenloyTgWsServiceExtension.TG_STATUS_WS -> R.string.tg_ws_status_ws
            status == EwenloyTgWsServiceExtension.TG_STATUS_DIRECT -> R.string.tg_ws_status_direct
            else -> R.string.tg_ws_status_idle
        }
        statusPref.summary = getString(summaryRes)
    }

    private fun showDiagnosticsDialog() {
        val ctx = requireContext()
        val tv = TextView(ctx).apply {
            setPadding(40, 30, 40, 30)
            textSize = 13f
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ewenloy_tg_diagnostics_dialog_title)
            .setView(tv)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        val updater = object : Runnable {
            override fun run() {
                val prefs = sharedPreferences
                val status = prefs?.getString(
                    EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY,
                    EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
                ) ?: EwenloyTgWsServiceExtension.TG_STATUS_DISABLED
                val last = prefs?.getString(
                    EwenloyTgWsServiceExtension.EWENLOY_TG_DIAGNOSTICS_KEY,
                    getString(R.string.ewenloy_tg_diagnostics_empty)
                ) ?: getString(R.string.ewenloy_tg_diagnostics_empty)
                tv.text = "status=$status\n$last"
                if (dialog.isShowing) uiHandler.postDelayed(this, 1000)
            }
        }
        dialog.setOnShowListener { uiHandler.post(updater) }
        dialog.setOnDismissListener { uiHandler.removeCallbacks(updater) }
        dialog.show()
    }

    private fun showSecretKeyDialog() {
        val ctx = requireContext()
        val prefs = sharedPreferences ?: return
        val secret = prefs.getString(EwenloyTgWsServiceExtension.PREF_SECRET_KEY, "") ?: ""
        val displaySecret = if (secret.isEmpty()) getString(R.string.tg_ws_secret_not_generated) else secret

        val tv = TextView(ctx).apply {
            setPadding(40, 30, 40, 30)
            textSize = 16f
            text = displaySecret
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.tg_ws_secret_dialog_title)
            .setView(tv)
            .setPositiveButton(R.string.tg_ws_secret_copy_btn) { _, _ ->
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("MTProto Secret", displaySecret))
                Toast.makeText(ctx, R.string.tg_ws_secret_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyToTelegramPackages(context: android.content.Context, url: String) {
        val telegramPackages = listOf(
            "org.telegram.messenger",
            "com.radolyn.ayugram",
            "com.exteragram.messenger",
            "org.telegram.plus",
            "ir.ilmili.telegraph",
            "org.telegram.BifToGram",
            "tw.nekomimi.nekogram",
            "xyz.nextalone.nagram",
            "uz.unnarsx.cherrygram",
            "org.telegram.mdgram",
            "org.forkclient.messenger.beta",
            "app.nicegram",
            "top.qwq2333.nullgram",
            "com.iMe.android",
            "ru.dahl.messenger",
            "com.scriptsaz.litegram",
            "org.thunderdog.challegram"
        )

        val pm = context.packageManager
        val availablePackages = telegramPackages.filter {
            try {
                pm.getPackageInfo(it, 0)
                true
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
        }

        if (availablePackages.isEmpty()) {
            Toast.makeText(context, R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
            return
        }

        val targetedIntents = availablePackages.map { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(pkg)
            }
        }

        if (targetedIntents.size == 1) {
            val intent = targetedIntents.first().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
            }
        } else {
            val chooserIntent = Intent.createChooser(targetedIntents.first(), getString(R.string.ewenloy_tg_choose_client))
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedIntents.drop(1).toTypedArray())
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}