package io.github.romanvht.byedpi.fragments

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.UISettings
import io.github.romanvht.byedpi.data.UISettings.DesyncMethod.*
import io.github.romanvht.byedpi.data.UISettings.HostsMode.*
import io.github.romanvht.byedpi.utility.*

class ByeDpiUISettingsFragment : PreferenceFragmentCompat() {
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
        setPreferencesFromResource(R.xml.byedpi_ui_settings, rootKey)

        setEditTestPreferenceListenerInt(
            "byedpi_max_connections",
            1,
            Short.MAX_VALUE.toInt()
        )
        setEditTestPreferenceListenerInt(
            "byedpi_buffer_size",
            1,
            Int.MAX_VALUE / 4
        )
        setEditTestPreferenceListenerInt("byedpi_default_ttl", 0, 255)
        setEditTestPreferenceListenerInt(
            "byedpi_split_position",
            Int.MIN_VALUE,
            Int.MAX_VALUE
        )
        setEditTestPreferenceListenerInt("byedpi_fake_ttl", 1, 255)
        setEditTestPreferenceListenerInt(
            "byedpi_tlsrec_position",
            2 * Short.MIN_VALUE,
            2 * Short.MAX_VALUE,
        )

        findPreferenceNotNull<EditTextPreference>("byedpi_oob_data")
            .setOnBindEditTextListener {
                it.filters = arrayOf(android.text.InputFilter.LengthFilter(1))
            }

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

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        updateProxyLinkSummary()
        uiHandler.post(statusUpdater)
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        uiHandler.removeCallbacks(statusUpdater)
    }

    private fun updatePreferences() {
        val desyncMethod = findPreferenceNotNull<ListPreference>("byedpi_desync_method").value.let { UISettings.DesyncMethod.fromName(it) }
        val hostsMode = findPreferenceNotNull<ListPreference>("byedpi_hosts_mode").value.let { UISettings.HostsMode.fromName(it) }

        val hostsBlacklist = findPreferenceNotNull<EditTextPreference>("byedpi_hosts_blacklist")
        val hostsWhitelist = findPreferenceNotNull<EditTextPreference>("byedpi_hosts_whitelist")
        val desyncHttp = findPreferenceNotNull<CheckBoxPreference>("byedpi_desync_http")
        val desyncHttps = findPreferenceNotNull<CheckBoxPreference>("byedpi_desync_https")
        val desyncUdp = findPreferenceNotNull<CheckBoxPreference>("byedpi_desync_udp")
        val splitPosition = findPreferenceNotNull<EditTextPreference>("byedpi_split_position")
        val splitAtHost = findPreferenceNotNull<CheckBoxPreference>("byedpi_split_at_host")
        val ttlFake = findPreferenceNotNull<EditTextPreference>("byedpi_fake_ttl")
        val fakeSni = findPreferenceNotNull<EditTextPreference>("byedpi_fake_sni")
        val fakeOffset = findPreferenceNotNull<EditTextPreference>("byedpi_fake_offset")
        val oobChar = findPreferenceNotNull<EditTextPreference>("byedpi_oob_data")
        val udpFakeCount = findPreferenceNotNull<EditTextPreference>("byedpi_udp_fake_count")
        val hostMixedCase = findPreferenceNotNull<CheckBoxPreference>("byedpi_host_mixed_case")
        val domainMixedCase = findPreferenceNotNull<CheckBoxPreference>("byedpi_domain_mixed_case")
        val hostRemoveSpaces = findPreferenceNotNull<CheckBoxPreference>("byedpi_host_remove_spaces")
        val splitTlsRec = findPreferenceNotNull<CheckBoxPreference>("byedpi_tlsrec_enabled")
        val splitTlsRecPosition = findPreferenceNotNull<EditTextPreference>("byedpi_tlsrec_position")
        val splitTlsRecAtSni = findPreferenceNotNull<CheckBoxPreference>("byedpi_tlsrec_at_sni")

        hostsBlacklist.isVisible = hostsMode == Blacklist
        hostsWhitelist.isVisible = hostsMode == Whitelist

        val desyncEnabled = desyncMethod != None
        splitPosition.isVisible = desyncEnabled
        splitAtHost.isVisible = desyncEnabled

        val isFake = desyncMethod == Fake
        ttlFake.isVisible = isFake
        fakeSni.isVisible = isFake
        fakeOffset.isVisible = isFake

        val isOob = desyncMethod == OOB || desyncMethod == DISOOB
        oobChar.isVisible = isOob

        val desyncAllProtocols = !desyncHttp.isChecked && !desyncHttps.isChecked && !desyncUdp.isChecked

        val desyncHttpEnabled = desyncAllProtocols || desyncHttp.isChecked
        hostMixedCase.isEnabled = desyncHttpEnabled
        domainMixedCase.isEnabled = desyncHttpEnabled
        hostRemoveSpaces.isEnabled = desyncHttpEnabled

        val desyncUdpEnabled = desyncAllProtocols || desyncUdp.isChecked
        udpFakeCount.isEnabled = desyncUdpEnabled

        val desyncHttpsEnabled = desyncAllProtocols || desyncHttps.isChecked
        splitTlsRec.isEnabled = desyncHttpsEnabled
        val tlsRecEnabled = desyncHttpsEnabled && splitTlsRec.isChecked
        splitTlsRecPosition.isEnabled = tlsRecEnabled
        splitTlsRecAtSni.isEnabled = tlsRecEnabled

        updateTelegramRuntimeStatus()
        updateProxyLinkSummary()
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

    private fun updateProxyLinkSummary() {
        val prefs = sharedPreferences ?: return
        val linkPref = findPreferenceNotNull<Preference>("ewenloy_tg_open_proxy_link")
        val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        linkPref.summary = getString(
            if (tgWsEnabled) R.string.ewenloy_tg_open_proxy_summary_ws
            else R.string.ewenloy_tg_open_proxy_summary
        )
    }

    // Режим TG WS: MTProto WS proxy, ссылка t.me/proxy с secret=dd...
    // Иначе: обычный SOCKS5 прокси ByeDPI
    private fun openTelegramProxyLink() {
        val prefs = sharedPreferences ?: return
        val tgWsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)

        if (tgWsEnabled) {
            val port = EwenloyTgWsServiceExtension.TG_WS_PORT
            val secretKey = prefs.getString(EwenloyTgWsServiceExtension.PREF_SECRET_KEY, "") ?: ""
            val secret = if (secretKey.isEmpty()) "00000000000000000000000000000000" else secretKey
            applyToTelegramPackages("https://t.me/proxy?server=127.0.0.1&port=$port&secret=dd$secret")
        } else {
            val host = prefs.getString("byedpi_proxy_ip", "127.0.0.1") ?: "127.0.0.1"
            val port = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
            val tgUri = Uri.parse("tg://socks?server=$host&port=$port")

            try {
                startActivity(Intent(Intent.ACTION_VIEW, tgUri))
            } catch (_: Exception) {
                Toast.makeText(requireContext(), R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyToTelegramPackages(url: String) {
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
        val context = requireContext()
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
}
