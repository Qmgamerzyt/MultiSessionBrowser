package app.multisession.browser.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.core.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val core get() = BrowserApp.core()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<EditTextPreference>(Prefs.KEY_CUSTOM_SEARCH)?.setOnBindEditTextListener { it.hint = "https://example.com/search?q=%s" }

        findPreference<Preference>("about_webview")?.summary = core.isolation.describe(requireContext())
        findPreference<Preference>("copyright")?.summary = getString(R.string.copyright)

        findPreference<Preference>("clear_session_cookies")?.setOnPreferenceClickListener { confirm(R.string.clear_cookies) { s -> core.sessions.clearData(s, cookies = true, storage = false, cache = false, history = false) }; true }
        findPreference<Preference>("clear_session_storage")?.setOnPreferenceClickListener { confirm(R.string.clear_storage) { s -> core.sessions.clearData(s, cookies = false, storage = true, cache = false, history = false) }; true }
        findPreference<Preference>("clear_session_cache")?.setOnPreferenceClickListener { confirm(R.string.clear_cache) { s -> core.sessions.clearData(s, cookies = false, storage = false, cache = true, history = false) }; true }
        findPreference<Preference>("clear_session_history")?.setOnPreferenceClickListener { confirm(R.string.clear_history) { s -> core.sessions.clearData(s, cookies = false, storage = false, cache = false, history = true) }; true }
        findPreference<Preference>("clear_all_history")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.clear_all_history).setMessage(R.string.clear_all_history_confirm)
                .setPositiveButton(R.string.clear) { _, _ -> lifecycleScope.launch { core.persistNow { core.repo.history.clearAll() }; toast(R.string.done) } }
                .setNegativeButton(android.R.string.cancel, null).show()
            true
        }
    }

    private fun confirm(titleRes: Int, action: suspend (String) -> Unit) {
        val session = core.sessions.active.value ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(titleRes) + " · " + session.name)
            .setMessage(if (core.isolation.isIsolated) R.string.reset_session_confirm else R.string.reset_session_confirm_shared)
            .setPositiveButton(R.string.clear) { _, _ -> lifecycleScope.launch { action(session.id); toast(R.string.done) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) {
        view?.let { Snackbar.make(it, res, Snackbar.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        Prefs.registerListener(this)
    }

    override fun onPause() {
        super.onPause()
        Prefs.unregisterListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_THEME -> AppCompatDelegate.setDefaultNightMode(Prefs.nightMode())
            // The global default changed: the tab on screen has to follow it now. Tabs in the
            // background pick it up when they become displayed (TabManager.setDisplayed).
            Prefs.KEY_PAGE_SCALE -> app.multisession.browser.engine.PageScale.applyDisplayed(core)
        }
    }
}
