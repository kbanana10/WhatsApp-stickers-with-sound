package com.example.ui.screens

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.whatsapp.WebBridgeCallbacks
import com.example.whatsapp.WebSendBridge
import com.example.whatsapp.WebViewHolder
import com.example.whatsapp.WhatsAppLauncher

@Composable
fun WhatsAppWebScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(if (WebViewHolder.hasFinishedFirstLoad) 100 else 0) }
    var isLoading by remember { mutableStateOf(!WebViewHolder.hasFinishedFirstLoad) }
    var canGoBack by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val fileCallback = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    val waPortJs = remember {
        context.assets.open("wa_port.js").bufferedReader().use { it.readText() }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileCallback[0]?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileCallback[0] = null
    }

    BackHandler(enabled = canGoBack) {
        webViewHolder[0]?.goBack()
    }

    WebBridgeCallbacks.onProgress = { p ->
        progress = p
        isLoading = p < 100
    }
    WebBridgeCallbacks.onPageFinished = { view, _ ->
        isLoading = false
        canGoBack = view.canGoBack()
        if (WebSendBridge.pendingStickerBase64 != null) {
            handler.postDelayed(
                { trySendSound(webViewHolder, handler, waPortJs, 25) { statusText = it } },
                900L
            )
        }
    }
    WebBridgeCallbacks.onFileChooser = { _, cb, _ ->
        fileCallback[0]?.onReceiveValue(null)
        val pending = WebSendBridge.pendingStickerUri
        if (pending != null) {
            cb.onReceiveValue(arrayOf(pending))
            WebSendBridge.pendingStickerUri = null
            true
        } else {
            fileCallback[0] = cb
            picker.launch("*/*")
            true
        }
    }

    // Trigger send immediately when returning to an already-loaded WebView with a queued sticker
    LaunchedEffect(Unit) {
        if (WebViewHolder.hasFinishedFirstLoad && WebSendBridge.pendingStickerBase64 != null) {
            handler.postDelayed(
                { trySendSound(webViewHolder, handler, waPortJs, 25) { statusText = it } },
                350L
            )
        }
    }

    // Detach only on tab leave without destroying WebView so session stays intact
    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            fileCallback[0]?.onReceiveValue(null)
            fileCallback[0] = null
            WebViewHolder.detach()
            WebBridgeCallbacks.clear()
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = {
                    WebViewHolder.hasFinishedFirstLoad = false
                    isLoading = true
                    webViewHolder[0]?.reload()
                },
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
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_open_whatsapp_button")
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("افتح واتساب", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    trySendSound(webViewHolder, handler, waPortJs, 20) { statusText = it }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_inject_now_button")
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("حقن الآن", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    webViewHolder[0]?.let { wv ->
                        wv.clearCache(true)
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        WebViewHolder.hasFinishedFirstLoad = false
                        isLoading = true
                        statusText = null
                        wv.loadUrl("https://web.whatsapp.com/")
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_reset_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("ضبط", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        statusText?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
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
                    val wv = WebViewHolder.getOrCreate(ctx)
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    addView(
                        wv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webViewHolder[0] = wv
                    canGoBack = wv.canGoBack()
                }
            }
        )
    }
}

/**
 * Injects wa_port.js into WhatsApp Web's MAIN world, enables sound sticker gating locally,
 * uploads OGG audio, and sends the WebP + stickerAudio payload to self chat internally without page reload.
 */
private fun trySendSound(
    holder: Array<WebView?>,
    handler: Handler,
    waPortJs: String,
    retries: Int,
    onStatus: (String?) -> Unit
) {
    val wv = holder[0] ?: return
    val webp = WebSendBridge.pendingStickerBase64 ?: return
    val ogg = WebSendBridge.pendingOggBase64 ?: ""
    val info = WebSendBridge.pendingInfoJson ?: "{}"
    if (retries <= 0) {
        onStatus("انتهت مهلة الإرسال عبر واتساب ويب — تأكد من تسجيل الدخول ثم اضغط حقن الآن.")
        return
    }

    wv.evaluateJavascript(
        "(function(){return typeof window.require==='function'&&typeof window.__d==='function';})()"
    ) { ready ->
        if (ready == "true") {
            onStatus("جارٍ إرسال الملصق الصوتي عبر واتساب ويب...")
            wv.evaluateJavascript(waPortJs) { _ ->
                wv.evaluateJavascript("WAAS_startSendB64('$webp','$ogg',$info,'propio')") { startRes ->
                    if (startRes?.contains("STARTED") == true || startRes?.contains("BUSY") == true) {
                        pollSendState(holder, handler, waPortJs, retries, 30, onStatus)
                    } else {
                        handler.postDelayed(
                            { trySendSound(holder, handler, waPortJs, retries - 1, onStatus) },
                            1200L
                        )
                    }
                }
            }
        } else {
            handler.postDelayed(
                { trySendSound(holder, handler, waPortJs, retries - 1, onStatus) },
                1000L
            )
        }
    }
}

private fun pollSendState(
    holder: Array<WebView?>,
    handler: Handler,
    waPortJs: String,
    retries: Int,
    pollLeft: Int,
    onStatus: (String?) -> Unit
) {
    val wv = holder[0] ?: return
    if (pollLeft <= 0) {
        onStatus("انتهت مهلة انتظار الرفع — اضغط حقن الآن للمحاولة مجدداً.")
        return
    }
    wv.evaluateJavascript("(function(){return window.__WAAS_SEND_STATE||'IDLE';})()") { raw ->
        val state = raw?.trim('"') ?: "IDLE"
        when {
            state == "PENDING" -> {
                handler.postDelayed(
                    { pollSendState(holder, handler, waPortJs, retries, pollLeft - 1, onStatus) },
                    500L
                )
            }
            state.startsWith("OK:") -> {
                WebSendBridge.pendingStickerBase64 = null
                WebSendBridge.pendingOggBase64 = null
                WebSendBridge.pendingInfoJson = null
                onStatus("تم إرسال الملصق الصوتي إلى محادثتك بنجاح ✅")
            }
            state.contains("no-self-number") || state.contains("wa-not-loaded") -> {
                // Likely still on QR / initial boot screen; retry later
                handler.postDelayed(
                    { trySendSound(holder, handler, waPortJs, retries - 1, onStatus) },
                    1500L
                )
            }
            else -> {
                onStatus("خطأ بالإرسال: $state")
            }
        }
    }
}
