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
import androidx.core.content.edit
import androidx.preference.*
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.activities.TestActivity
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PrivateDnsState
import io.github.romanvht.byedpi.databinding.DialogDonateBinding
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.utility.*
import androidx.core.net.toUri

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName

        private const val DONATE_URL_AUTHOR = "https://dalink.to/ewenloy"
        private const val DONATE_URL_CLOUDFLIPS = "https://pay.cloudtips.ru/p/92c754db"
        private const val DONATE_URL_BOOSTY = "https://boosty.to/romanvht/donate"
        private const val DONATE_URL_KEKSIK = "https://t.me/romanvht_donate_bot"
        private const val CONTRIBUTOR_URL_HUFREA = "https://github.com/hufrea"
        private const val CONTRIBUTOR_URL_FLOWSEAL = "https://github.com/Flowseal"
        private const val CONTRIBUTOR_URL_AMURCANOV = "https://github.com/amurcanov"
    }

    private val tgWsExt = EwenloyTgWsServiceExtension()
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

        tgWsExt.initialize(requireContext(), requireContext().getPreferences())

        findPreferenceNotNull<Preference>("ewenloy_tg_open_proxy_link")
            .setOnPreferenceClickListener {
                openTelegramProxyLink()
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
                scheduleTgWsRefresh()
                true
            }

        findPreferenceNotNull<SwitchPreference>("tg_ws_cf_enabled")
            .setOnPreferenceChangeListener { _, _ ->
                scheduleTgWsRefresh()
                true
            }

        findPreferenceNotNull<ListPreference>("tg_ws_pool_size")
            .setOnPreferenceChangeListener { _, _ ->
                scheduleTgWsRefresh()
                true
            }

        findPreferenceNotNull<Preference>("tg_ws_secret_key")
            .setOnPreferenceClickListener {
                showSecretKeyDialog()
                true
            }

        findPreferenceNotNull<Preference>("battery_optimization")
            .setOnPreferenceClickListener {
                PermissionUtils.requestBatteryOptimization(requireContext())
                true
            }

        findPreferenceNotNull<Preference>("storage_access")
            .setOnPreferenceClickListener {
                PermissionUtils.requestStorageAccess(this)
                true
            }

        findPreferenceNotNull<Preference>("donate")
            .setOnPreferenceClickListener {
                showDonateDialog()
                true
            }

        findPreferenceNotNull<Preference>("dns_ip_picker")
            .setOnPreferenceClickListener {
                if (PrivateDnsUtils.getState(requireContext()) is PrivateDnsState.Configured) {
                    updatePreferences()
                    PrivateDnsUtils.openSettings(requireContext())
                } else {
                    showDnsDialog()
                }
                true
            }

        findPreferenceNotNull<Preference>("private_dns")
            .setOnPreferenceClickListener {
                PrivateDnsUtils.openSettings(requireContext())
                true
            }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME
        findPreferenceNotNull<Preference>("byedpi_version").summary = "0.17.3 (ba53229)"

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.post(statusUpdater)
        updatePreferences()
        updateProxyLinkSummary()
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.removeCallbacks(statusUpdater)
    }

    private fun updatePreferences() {
        val cmdEnable = findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings").isChecked
        val mode = findPreferenceNotNull<ListPreference>("byedpi_mode").value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<Preference>("dns_ip_picker")
        val dnsEdit = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val privateDns = findPreferenceNotNull<Preference>("private_dns")
        val privateDnsState = PrivateDnsUtils.getState(requireContext())
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

        dns.summary = dnsSummary()
        dnsEdit.isVisible = false
        privateDns.summary = (privateDnsState as? PrivateDnsState.Configured)?.hostname
        privateDns.isVisible = mode == Mode.VPN && privateDnsState is PrivateDnsState.Configured

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = privateDnsState !is PrivateDnsState.Configured
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

        if (PermissionUtils.isBatteryOptimizationDisabled(requireContext())) {
            batteryOptimization.summary = getString(R.string.battery_optimization_disabled_summary)
        } else {
            batteryOptimization.summary = getString(R.string.battery_optimization_summary)
        }

        if (PermissionUtils.hasStorageAccess(requireContext())) {
            storageAccess.summary = getString(R.string.storage_access_allowed_summary)
        } else {
            storageAccess.summary = getString(R.string.storage_access_summary)
        }

        updateTelegramRuntimeStatus()
    }

    private val dnsPresets: List<Pair<String, String>>
        get() = listOf(
            "" to getString(R.string.dns_system),
            "8.8.8.8" to getString(R.string.dns_google),
            "1.1.1.1" to getString(R.string.dns_cloudflare),
            "9.9.9.9" to getString(R.string.dns_quad9),
            "77.88.8.8" to getString(R.string.dns_yandex),
        )

    private fun dnsSummary(): String {
        val ip = sharedPreferences?.getStringNotNull("dns_ip", "1.1.1.1") ?: "1.1.1.1"
        val preset = dnsPresets.firstOrNull { it.first == ip } ?: return ip
        return if (preset.first.isBlank()) preset.second else "${preset.second} ($ip)"
    }

    private fun showDnsDialog() {
        val context = requireContext()
        val presets = dnsPresets

        val items = (presets.map { it.second } + getString(R.string.dns_custom)).toTypedArray()
        val dnsPref = findPreferenceNotNull<EditTextPreference>("dns_ip")

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.dbs_ip_setting))
            .setItems(items) { _, which ->
                if (which < presets.size) {
                    sharedPreferences?.edit { putString("dns_ip", presets[which].first) }
                } else {
                    onDisplayPreferenceDialog(dnsPref)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDonateDialog() {
        val binding = DialogDonateBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.donate))
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        binding.donateAuthorButton.setOnClickListener {
            openUrl(DONATE_URL_AUTHOR)
            dialog.dismiss()
        }
        binding.donateContributorsButton.setOnClickListener {
            dialog.dismiss()
            showContributorsDialog()
        }

        dialog.show()
    }

    private fun showContributorsDialog() {
        val items = arrayOf(
            getString(R.string.donate_contrib_romanvht),
            getString(R.string.donate_contrib_hufrea),
            getString(R.string.donate_contrib_flowseal),
            getString(R.string.donate_contrib_amurcanov),
        )

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.donate_contributors_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUpstreamDonateDialog()
                    1 -> openUrl(CONTRIBUTOR_URL_HUFREA)
                    2 -> openUrl(CONTRIBUTOR_URL_FLOWSEAL)
                    3 -> openUrl(CONTRIBUTOR_URL_AMURCANOV)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUpstreamDonateDialog() {
        val items = arrayOf(
            getString(R.string.donate_cloudtips),
            getString(R.string.donate_boosty),
            getString(R.string.donate_telegram_bot),
        )

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.donate_support_romanvht))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openUrl(DONATE_URL_CLOUDFLIPS)
                    1 -> openUrl(DONATE_URL_BOOSTY)
                    2 -> openUrl(DONATE_URL_KEKSIK)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }

    private fun scheduleTgWsRefresh() {
        val prefs = sharedPreferences ?: return
        uiHandler.postDelayed({ tgWsExt.refreshFromPreferences(prefs) }, 150)
    }

    private fun updateProxyLinkSummary() {
        val prefs = sharedPreferences ?: return
        val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        val linkPref = findPreferenceNotNull<Preference>("ewenloy_tg_open_proxy_link")
        linkPref.summary = getString(
            if (tgWsEnabled) R.string.ewenloy_tg_open_proxy_summary_ws
            else R.string.ewenloy_tg_open_proxy_summary
        )
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

    private fun openTelegramProxyLink() {
        val prefs = sharedPreferences ?: return
        val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)

        if (tgWsEnabled) {
            // MTProto WS Proxy — используем t.me/proxy?...&secret=
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
    }

    private fun applyToTelegramPackages(context: Context, url: String) {
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
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(pkg) }
        }
        if (targetedIntents.size == 1) {
            val intent = targetedIntents.first().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
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
