package com.example.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.BuildConfig

/**
 * Callbacks provided by the currently visible WhatsAppWebScreen.
 * The singleton WebView delegates here so its lifecycle is independent of tab switches.
 */
object WebBridgeCallbacks {
    var onProgress: ((Int) -> Unit)? = null
    var onPageFinished: ((WebView, String) -> Unit)? = null
    var onFileChooser: ((WebView, ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    fun clear() {
        onProgress = null
        onPageFinished = null
        onFileChooser = null
    }
}

/**
 * App-scoped singleton WebView for WhatsApp Web.
 * Switching tabs detaches the view without destroying it, preserving the session and open chat.
 */
object WebViewHolder {
    private var webView: WebView? = null

    @Volatile
    var hasFinishedFirstLoad: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context): WebView {
        webView?.let { return it }
        val appCtx = context.applicationContext

        val wv = WebView(appCtx)
        wv.clipToOutline = true
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = 100
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty().lowercase()
                val isWhatsAppHost = host == "whatsapp.com" || host.endsWith(".whatsapp.com")
                return if (isWhatsAppHost) {
                    false
                } else {
                    runCatching {
                        view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, request.url)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                hasFinishedFirstLoad = true
                WebBridgeCallbacks.onPageFinished?.invoke(view, url)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                WebBridgeCallbacks.onProgress?.invoke(newProgress)
            }

            override fun onShowFileChooser(
                view: WebView,
                cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                return WebBridgeCallbacks.onFileChooser?.invoke(view, cb, params)
                    ?: run {
                        cb.onReceiveValue(null)
                        false
                    }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val host = request.origin?.host.orEmpty().lowercase()
                if (host == "whatsapp.com" || host.endsWith(".whatsapp.com")) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        wv.loadUrl("https://web.whatsapp.com/")
        webView = wv
        return wv
    }

    /** Detaches the WebView from its parent container without destroying it so the session stays alive. */
    fun detach() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }
}
