package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppSettings
import io.github.romanvht.byedpi.fragments.ByeDpiCMDSettingsFragment
import io.github.romanvht.byedpi.fragments.ByeDpiUISettingsFragment
import io.github.romanvht.byedpi.fragments.MainSettingsFragment
import io.github.romanvht.byedpi.utility.SettingsUtils
import io.github.romanvht.byedpi.utility.SettingsUtils.Section
import java.io.File

class SettingsActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private var exportSections = Section.entries.toSet()

    /**
     * Переход по `app:fragment` из XML настроек.
     *
     * Без этой реализации нажатие на пункт с привязкой к фрагменту (в том числе
     * «Настройки TG WS Proxy») не открывало ничего вообще.
     *
     * Фрагмент создаём БЕЗ arguments: если передать ключ пункта как rootKey,
     * PreferenceFragmentCompat ищет PreferenceScreen с таким ключом, не находит
     * (в XML корень описан через tag или другой ключ) и падает с
     * IllegalArgumentException — это и был вылет при открытии настроек TG WS.
     */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val className = pref.fragment ?: return false
        val fragment = try {
            supportFragmentManager.fragmentFactory.instantiate(classLoader, className)
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось открыть экран настроек $className", e)
            Toast.makeText(this, R.string.settings_screen_failed, Toast.LENGTH_SHORT).show()
            return true
        }

        pref.title?.let { supportActionBar?.title = it }
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    private companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exportSections = savedInstanceState
            ?.getStringArrayList("export_sections")
            ?.map(Section::valueOf)
            ?.toSet()
            ?: Section.entries.toSet()

        setContentView(R.layout.activity_settings)
        setupToolbar()

        if (savedInstanceState == null) {
            val openFragment = intent.getStringExtra("open_fragment")

            when (openFragment) {
                "cmd" -> {
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings, ByeDpiCMDSettingsFragment())
                        .commit()
                }
                "ui" -> {
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings, ByeDpiUISettingsFragment())
                        .commit()
                }
                else -> {
                    supportFragmentManager
                        .beginTransaction()
                        .replace(R.id.settings, MainSettingsFragment())
                        .commit()
                }
            }
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            "export_sections",
            ArrayList(exportSections.map { it.name })
        )
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_settings, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressedDispatcher.onBackPressed()
            true
        }

        R.id.action_reset_settings -> {
            SettingsUtils.resetSettings(this) {
                recreate()
            }
            true
        }

        R.id.action_export_settings -> {
            showSectionSelectionDialog(
                title = R.string.export_settings,
                availableSections = Section.entries.toSet()
            ) { sections ->
                exportSections = sections
                openFile(FileActivity.MODE_CREATE)
            }
            true
        }

        R.id.action_import_settings -> {
            openFile(FileActivity.MODE_OPEN)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::handleFileResult
    )

    private fun handleFileResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val data = result.data ?: return
        val mode = data.getStringExtra(FileActivity.EXTRA_MODE)
        val uri = data.data
        val path = data.getStringExtra(FileActivity.EXTRA_PATH)
        val file = if (path == null) null else File(path)

        when (mode) {
            FileActivity.MODE_OPEN -> {
                val settings = when {
                    uri != null -> SettingsUtils.readSettings(this, uri)
                    file != null -> SettingsUtils.readSettings(this, file)
                    else -> null
                } ?: return
                val availableSections = SettingsUtils.getAvailableSections(settings)
                if (availableSections.isNotEmpty()) {
                    showImportDialog(settings, availableSections)
                }
            }

            FileActivity.MODE_CREATE -> when {
                uri != null -> SettingsUtils.exportSettings(this, uri, exportSections)
                file != null -> SettingsUtils.exportSettings(this, file, exportSections)
            }
        }
    }

    private fun openFile(mode: String) {
        val intent = Intent(this, FileActivity::class.java)
        intent.putExtra(FileActivity.EXTRA_MODE, mode)
        intent.putExtra(FileActivity.EXTRA_TYPE, FileActivity.TYPE_SETTINGS)
        fileLauncher.launch(intent)
    }

    private fun showImportDialog(settings: AppSettings, availableSections: Set<Section>) {
        showSectionSelectionDialog(
            title = R.string.import_settings,
            availableSections = availableSections
        ) { sections ->
            SettingsUtils.importSettings(this, settings, sections) {
                recreate()
            }
        }
    }

    private fun showSectionSelectionDialog(
        @StringRes title: Int,
        availableSections: Set<Section>,
        onConfirmed: (Set<Section>) -> Unit
    ) {
        val sections = Section.entries.filter { it in availableSections }
        val checkedItems = BooleanArray(sections.size) { true }
        val labels = sections.map { getString(it.label) }.toTypedArray()

        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checkedItems.any { it }
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            positiveButton.setOnClickListener {
                val selected = sections
                    .filterIndexed { index, _ -> checkedItems[index] }
                    .toSet()

                if (selected.isNotEmpty()) {
                    dialog.dismiss()
                    onConfirmed(selected)
                }
            }

            positiveButton.isEnabled = checkedItems.any { it }
        }

        dialog.show()
    }

    @get:StringRes
    private val Section.label: Int
        get() = when (this) {
            Section.SETTINGS -> R.string.backup_settings
            Section.HISTORY -> R.string.backup_command_history
            Section.APPS -> R.string.backup_selected_apps
            Section.DOMAIN_LISTS -> R.string.backup_domain_lists
        }

}
