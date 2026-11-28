package com.example.ui.screens

import android.annotation.SuppressLint
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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BuildConfig
import com.example.whatsapp.WebSendBridge
import com.example.whatsapp.WhatsAppLauncher

/**
 * Full-featured WhatsApp Web browser tab:
 * - clipToOutline + FrameLayout: avoids Compose + WebView black screen bug.
 * - Modern Desktop Chrome UA: passes WhatsApp Web browser check instead of stalling on E2EE screen.
 * - File chooser integration: auto-attaches queued WebP sticker from WebSendBridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WhatsAppWebScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val fileCallback = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileCallback[0]?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileCallback[0] = null
    }

    BackHandler(enabled = canGoBack) {
        webViewHolder[0]?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback[0]?.onReceiveValue(null)
            fileCallback[0] = null
            webViewHolder[0]?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
            webViewHolder[0] = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { webViewHolder[0]?.reload() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_refresh_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("تحديث", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = { WhatsAppLauncher.openWhatsApp(context) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("web_open_whatsapp_button")
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("افتح واتساب", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    webViewHolder[0]?.let { wv ->
                        wv.clearCache(true)
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        wv.loadUrl("https://web.whatsapp.com/")
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_reset_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("إعادة ضبط", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("whatsapp_web_view"),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    setBackgroundColor(Color.WHITE)
                    val wv = WebView(ctx)
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
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val host = request.url.host.orEmpty().lowercase()
                            val isWhatsAppHost = host == "whatsapp.com" || host.endsWith(".whatsapp.com")
                            return if (isWhatsAppHost) {
                                false
                            } else {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, request.url)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                                true
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            isLoading = false
                            canGoBack = view.canGoBack()
                        }
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                            isLoading = newProgress < 100
                        }

                        override fun onShowFileChooser(
                            view: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams
                        ): Boolean {
                            fileCallback[0]?.onReceiveValue(null)
                            val pending = WebSendBridge.pendingStickerUri
                            if (pending != null) {
                                callback.onReceiveValue(arrayOf(pending))
                                WebSendBridge.pendingStickerUri = null
                                return true
                            }
                            fileCallback[0] = callback
                            picker.launch("*/*")
                            return true
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
                    addView(
                        wv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webViewHolder[0] = wv
                }
            }
        )
    }
}
