package com.chromium.xenon

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import java.io.File

/**
 * Wipes everything a browsing session leaves behind: cookies, local/session storage,
 * IndexedDB, form data, HTTP auth cache and the on-disk cache.
 *
 * Downloads are intentionally left alone — they are user files.
 */
object BrowserData {

    @Suppress("DEPRECATION")
    fun clearEverything(context: Context) {
        val appContext = context.applicationContext

        // Cookies (async on the WebView's cookie thread, then flush).
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        // LocalStorage / IndexedDB / quota-managed storage.
        WebStorage.getInstance().deleteAllData()

        // Form autofill data and HTTP auth credentials remembered by the WebView.
        val database = WebViewDatabase.getInstance(appContext)
        database.clearFormData()
        database.clearHttpAuthUsernamePassword()

        // On-disk cache + a throwaway WebView to flush its own history/form memory.
        clearDiskCache(appContext)

        val throwaway = WebView(appContext)
        throwaway.clearCache(true)
        throwaway.clearFormData()
        throwaway.clearHistory()
        throwaway.destroy()
    }

    private fun clearDiskCache(context: Context) {
        context.cacheDir?.let { deleteContents(it) }
        context.externalCacheDir?.let { deleteContents(it) }
    }

    private fun deleteContents(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) deleteContents(child)
            child.delete()
        }
    }
}
