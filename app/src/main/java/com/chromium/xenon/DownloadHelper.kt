package com.chromium.xenon

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

/**
 * Hands file downloads off to the platform DownloadManager so they survive the
 * WebView, appear in the notification shade and land in the public Downloads folder.
 */
object DownloadHelper {

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val cleaned = guessed.replace(ILLEGAL_FILE_CHARS, "_").trim()
        return cleaned.ifBlank { "xenon-download" }
    }

    /**
     * Enqueues the download and returns the DownloadManager id.
     *
     * @throws IllegalArgumentException if the URL cannot be parsed.
     * @throws SecurityException if storage permission is missing on API <= 28.
     */
    fun enqueue(
        context: Context,
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String?
    ): Long {
        val fileName = fileNameFor(url, contentDisposition, mimeType)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(context.getString(R.string.download_description))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)

            if (!userAgent.isNullOrBlank()) {
                addRequestHeader("User-Agent", userAgent)
            }

            // Carry the session cookies across so authenticated downloads work.
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                addRequestHeader("Cookie", cookie)
            }
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }
}
