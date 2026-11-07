package com.chromium.xenon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.chromium.xenon.databinding.ActivityMainBinding

/**
 * The whole browser surface: address bar, WebView, navigation state, error state
 * and download hand-off. One activity, one tab, no surprises.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var isLoading = false
    private var currentUrl: String? = null
    private var defaultUserAgent: String = ""

    private data class PendingDownload(
        val url: String,
        val contentDisposition: String?,
        val mimeType: String?,
        val userAgent: String?
    )

    private var pendingDownload: PendingDownload? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val download = pendingDownload
            pendingDownload = null
            when {
                granted && download != null -> startDownload(download)
                !granted -> toast(getString(R.string.download_permission_denied))
            }
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            goBackOrExit()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        configureWebView()
        wireTopBar()
        onBackPressedDispatcher.addCallback(this, backCallback)
        restoreOrLoadHome(savedInstanceState)
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        applyWebsitePreferences()
    }

    override fun onPause() {
        binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
        outState.putString(STATE_CURRENT_URL, currentUrl)
    }

    override fun onDestroy() {
        if (isFinishing) {
            binding.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        val settings = webView.settings

        defaultUserAgent = settings.userAgentString ?: ""

        settings.javaScriptEnabled = prefs.javaScriptEnabled
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.textZoom = 100

        // API 26+ so Safe Browsing is always available on Xenon's minimum SDK.
        settings.safeBrowsingEnabled = true

        applyUserAgent(settings)

        // Hardware rendering for the compositor (the manifest flag covers the window).
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        webView.webViewClient = XenonWebViewClient()
        webView.webChromeClient = XenonChromeClient()
        webView.isVerticalScrollBarEnabled = true

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(url, userAgent, contentDisposition, mimeType)
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    /** Re-applies settings that can change while the app is backgrounded. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebsitePreferences() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = prefs.javaScriptEnabled
        applyUserAgent(settings)
    }

    private fun applyUserAgent(settings: WebSettings) {
        settings.userAgentString = if (prefs.desktopMode) {
            Prefs.UA_DESKTOP
        } else {
            "$defaultUserAgent ${getString(R.string.ua_suffix)}"
        }
    }

    // ---------------------------------------------------------------- top bar

    private fun wireTopBar() {
        binding.btnBack.setOnClickListener { goBackOrExit() }
        binding.btnBack.setOnLongClickListener {
            binding.webView.clearHistory()
            updateNavigationState()
            toast(getString(R.string.action_back))
            true
        }
        binding.btnGo.setOnClickListener { onGoClicked() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnRetry.setOnClickListener {
            hideError()
            reloadCurrent()
        }

        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                submitAddressBar()
                true
            } else {
                false
            }
        }

        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.addressBar.text.isNullOrBlank()) {
                currentUrl?.let { setAddressText(it) }
            }
        }

        updateGoButton()
        updateNavigationState()
    }

    private fun submitAddressBar() {
        val typed = binding.addressBar.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return

        val target = UrlUtils.normalize(typed, prefs.searchUrlTemplate)
        if (target.isEmpty()) return

        hideKeyboard()
        binding.addressBar.clearFocus()
        if (target == Prefs.HOME_INTERNAL) {
            loadHome()
        } else {
            hideError()
            load(target)
        }
    }

    private fun onGoClicked() {
        if (isLoading) {
            binding.webView.stopLoading()
            isLoading = false
            binding.progressBar.isVisible = false
            updateGoButton()
            return
        }

        val typed = binding.addressBar.text?.toString()?.trim().orEmpty()
        if (binding.addressBar.hasFocus() && typed.isNotEmpty() && typed != currentUrl) {
            submitAddressBar()
            return
        }

        reloadCurrent()
    }

    private fun goBackOrExit() {
        if (binding.errorContainer.isVisible) {
            hideError()
            return
        }
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }

    private fun updateGoButton() {
        binding.btnGo.setImageResource(
            if (isLoading) R.drawable.ic_stop else R.drawable.ic_refresh
        )
        binding.btnGo.contentDescription = getString(
            if (isLoading) R.string.action_stop else R.string.action_reload
        )
    }

    private fun updateNavigationState() {
        binding.btnBack.alpha = if (binding.webView.canGoBack()) 1f else 0.4f
    }

    private fun setAddressText(url: String) {
        if (url.startsWith(ASSET_PREFIX)) {
            binding.addressBar.setText("")
            return
        }
        if (binding.addressBar.hasFocus()) return
        binding.addressBar.setText(url)
        binding.addressBar.setSelection(binding.addressBar.text?.length ?: 0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
    }

    // ---------------------------------------------------------------- navigation

    private fun load(url: String) {
        currentUrl = url
        binding.webView.loadUrl(url)
    }

    private fun loadHome() {
        hideError()
        val home = prefs.homepage
        if (home == Prefs.HOME_INTERNAL) {
            val assetUrl = "$ASSET_PREFIX?engine=${prefs.searchEngine}"
            currentUrl = assetUrl
            binding.webView.loadUrl(assetUrl)
        } else {
            load(home)
        }
    }

    private fun reloadCurrent() {
        val url = currentUrl
        if (url.isNullOrBlank() || url == "about:blank") {
            loadHome()
        } else {
            load(url)
        }
    }

    private fun restoreOrLoadHome(state: Bundle?) {
        if (state == null) {
            loadHome()
            return
        }
        val restored = binding.webView.restoreState(state)
        val savedUrl = state.getString(STATE_CURRENT_URL)
        if (restored != null && !savedUrl.isNullOrBlank()) {
            currentUrl = savedUrl
            setAddressText(savedUrl)
            updateNavigationState()
        } else {
            loadHome()
        }
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        hideError()
        load(uri.toString())
    }

    // ---------------------------------------------------------------- error state

    private fun showError(title: String, message: String) {
        binding.errorTitle.text = title
        binding.errorMessage.text = message
        binding.errorContainer.isVisible = true
        binding.errorContainer.alpha = 0f
        binding.errorContainer.animate().alpha(1f).setDuration(180L).start()
        binding.webView.isVisible = false
        binding.progressBar.isVisible = false
        isLoading = false
        updateGoButton()
    }

    private fun hideError() {
        binding.errorContainer.isVisible = false
        binding.webView.isVisible = true
    }

    private fun describeNetworkError(code: Int, description: String?): Pair<String, String> {
        return when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> {
                if (isOffline()) {
                    getString(R.string.error_offline_title) to
                        getString(R.string.error_offline_message)
                } else {
                    getString(R.string.error_host_title) to
                        getString(R.string.error_host_message)
                }
            }
            WebViewClient.ERROR_TIMEOUT -> getString(R.string.error_timeout_title) to
                getString(R.string.error_timeout_message)
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO -> {
                if (isOffline()) {
                    getString(R.string.error_offline_title) to
                        getString(R.string.error_offline_message)
                } else {
                    getString(R.string.error_title) to
                        getString(R.string.error_generic_message, description ?: "connect")
                }
            }
            else -> getString(R.string.error_title) to
                getString(R.string.error_generic_message, description ?: code.toString())
        }
    }

    private fun isOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------- downloads

    private fun onDownloadRequested(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val download = PendingDownload(url, contentDisposition, mimeType, userAgent)

        val needsLegacyPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

        if (needsLegacyPermission) {
            pendingDownload = download
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            startDownload(download)
        }
    }

    private fun startDownload(download: PendingDownload) {
        try {
            DownloadHelper.enqueue(
                context = this,
                url = download.url,
                contentDisposition = download.contentDisposition,
                mimeType = download.mimeType,
                userAgent = download.userAgent
            )
            toast(getString(R.string.download_started))
        } catch (e: Exception) {
            toast(getString(R.string.download_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    // ---------------------------------------------------------------- external links

    private fun openInOtherApp(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.error_no_app))
        } catch (e: Exception) {
            toast(getString(R.string.error_no_app))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- clients

    private inner class XenonWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return true

            // Everything a page can legitimately navigate to stays in-app.
            if (scheme == "http" || scheme == "https" ||
                scheme == "about" || scheme == "data" || scheme == "blob"
            ) {
                return false
            }

            // javascript: from a page is blocked outright.
            if (scheme == "javascript") return true

            // mailto:, tel:, sms:, intent:, market:, geo: ... go to the OS.
            if (request.isForMainFrame) {
                openInOtherApp(uri)
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isLoading = true
            updateGoButton()
            binding.progressBar.isVisible = true
            binding.progressBar.progress = 5
            if (!url.startsWith(ASSET_PREFIX)) {
                currentUrl = url
                setAddressText(url)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            isLoading = false
            updateGoButton()
            binding.progressBar.isVisible = false
            updateNavigationState()
            if (!url.startsWith(ASSET_PREFIX)) {
                currentUrl = url
                setAddressText(url)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            updateNavigationState()
            if (!url.startsWith(ASSET_PREFIX)) {
                currentUrl = url
                setAddressText(url)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return

            val (title, message) = describeNetworkError(
                error.errorCode,
                error.description?.toString()
            )
            showError(title, message)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            // Never proceed: an invalid certificate is a hard stop in Xenon.
            handler.cancel()
            showError(
                getString(R.string.error_ssl_title),
                getString(R.string.error_ssl_message, error.url ?: "")
            )
        }
    }

    private inner class XenonChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (binding.errorContainer.isVisible) return
            binding.progressBar.progress = newProgress
            binding.progressBar.isVisible = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            super.onReceivedTitle(view, title)
            // Kept intentionally minimal: the address bar always shows the URL, never
            // page-supplied text, so a page cannot spoof the address it is on.
        }
    }

    companion object {
        private const val ASSET_PREFIX = "file:///android_asset/home.html"
        private const val STATE_CURRENT_URL = "xenon_state_current_url"
    }
}
