package app.multisession.browser.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * v2.2.0-stable: the About page. All About content that used to sit inline in Settings
 * (engine & session isolation status, copyright) plus the app version now lives here,
 * reached from the Settings screen's "About" button.
 */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val info = packageManager.getPackageInfo(packageName, 0)
        findViewById<android.widget.TextView>(R.id.aboutVersionValue).text =
            "${info.versionName} (${info.longVersionCode})"
        findViewById<android.widget.TextView>(R.id.aboutIsolationValue).text =
            BrowserApp.core().isolation.describe(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}
