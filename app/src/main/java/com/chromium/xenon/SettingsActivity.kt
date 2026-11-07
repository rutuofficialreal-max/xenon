package com.chromium.xenon

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chromium.xenon.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Everything configurable in Xenon lives here. Changes are applied immediately —
 * there is no Save button, which is one less way to lose a setting.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** Guards the initial [bindValues] pass from writing defaults back. */
    private var suppressListeners = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        bindValues()
        wireListeners()
    }

    override fun onPause() {
        saveHomepage()
        super.onPause()
    }

    private fun bindValues() {
        suppressListeners = true

        val checkedId = when (prefs.searchEngine) {
            Prefs.ENGINE_DUCKDUCKGO -> R.id.rbDuckDuckGo
            Prefs.ENGINE_BRAVE -> R.id.rbBrave
            Prefs.ENGINE_STARTPAGE -> R.id.rbStartpage
            else -> R.id.rbGoogle
        }
        binding.engineGroup.check(checkedId)

        val home = prefs.homepage
        binding.etHomepage.setText(if (home == Prefs.HOME_INTERNAL) "" else home)
        binding.swJavaScript.isChecked = prefs.javaScriptEnabled
        binding.swDesktop.isChecked = prefs.desktopMode

        suppressListeners = false
    }

    private fun wireListeners() {
        binding.engineGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.searchEngine = when (checkedId) {
                R.id.rbDuckDuckGo -> Prefs.ENGINE_DUCKDUCKGO
                R.id.rbBrave -> Prefs.ENGINE_BRAVE
                R.id.rbStartpage -> Prefs.ENGINE_STARTPAGE
                else -> Prefs.ENGINE_GOOGLE
            }
        }

        binding.swJavaScript.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.javaScriptEnabled = isChecked
        }

        binding.swDesktop.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.desktopMode = isChecked
        }

        binding.btnClearData.setOnClickListener { confirmClearData() }

        binding.btnReset.setOnClickListener {
            prefs.resetToDefaults()
            bindValues()
            toast(getString(R.string.settings_reset_done))
        }
    }

    private fun saveHomepage() {
        val typed = binding.etHomepage.text?.toString()?.trim().orEmpty()

        if (typed.isEmpty()) {
            prefs.homepage = Prefs.HOME_INTERNAL
            return
        }

        val normalized = UrlUtils.normalizeHomepage(typed)
        if (normalized == null) {
            toast(getString(R.string.settings_homepage_invalid))
            val current = prefs.homepage
            binding.etHomepage.setText(if (current == Prefs.HOME_INTERNAL) "" else current)
        } else {
            prefs.homepage = normalized
        }
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_confirm_title)
            .setMessage(R.string.settings_clear_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_clear_confirm_positive) { _, _ ->
                BrowserData.clearEverything(this)
                toast(getString(R.string.settings_clear_done))
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
