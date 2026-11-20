package io.github.romanvht.byedpi.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.utility.ClipboardUtils
import io.github.romanvht.byedpi.utility.findPreferenceNotNull

class TgWsSettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val TAG = "TgWsSettingsFragment"
        private const val SECRET_KEY = "tg_ws_secret_key"
    }

    private val tgWsExt = EwenloyTgWsServiceExtension()
    private val prefsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != SECRET_KEY && key?.startsWith("tg_ws_secret") != true) {
                scheduleTgWsRefresh()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.tg_ws_settings, rootKey)

        tgWsExt.initialize(requireContext(), requireContext().getSharedPreferences(
            requireContext().packageName + "_preferences",
            android.content.Context.MODE_PRIVATE
        ))

        // Секрет: показать диалог с копированием
        findPreferenceNotNull<Preference>(SECRET_KEY)
            .setOnPreferenceClickListener { showSecretDialog(); true }

        // Обновить summary EditTextPreference DC при изменении
        listOf(
            "tg_ws_dc1", "tg_ws_dc2", "tg_ws_dc3", "tg_ws_dc4", "tg_ws_dc5", "tg_ws_dc203",
            "tg_ws_dc1m", "tg_ws_dc2m", "tg_ws_dc3m", "tg_ws_dc4m", "tg_ws_dc5m", "tg_ws_dc203m",
            "tg_ws_cf_domain", "tg_ws_cf_worker_domains", "tg_ws_faketls_domain"
        ).forEach { key ->
            findPreference<EditTextPreference>(key)?.let { updateEditSummary(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    private fun updateEditSummary(pref: EditTextPreference) {
        val value = pref.text ?: ""
        pref.summary = if (value.isEmpty()) pref.dialogMessage ?: "" else value
    }

    private fun scheduleTgWsRefresh() {
        // Небольшая задержка, чтобы prefs успели сохраниться на диск
        Handler(Looper.getMainLooper()).postDelayed({
            tgWsExt.refreshFromPreferences(preferenceManager.sharedPreferences ?: return@postDelayed)
        }, 100)
    }

    private fun showSecretDialog() {
        val ctx = context ?: return
        val prefs = preferenceManager.sharedPreferences ?: return

        val full = try {
            EwenloyTgWsServiceExtension.ensureSecret(prefs)
        } catch (_: Exception) {
            prefs.getString(SECRET_KEY, null)
        } ?: return

        AlertDialog.Builder(ctx)
            .setTitle(R.string.tg_ws_secret_dialog_title)
            .setMessage(full)
            .setPositiveButton(R.string.tg_ws_secret_copy_btn) { _, _ ->
                ClipboardUtils.copy(ctx, full)
                Toast.makeText(ctx, R.string.tg_ws_secret_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close_app) { d, _ -> d.dismiss() }
            .show()
    }
}
