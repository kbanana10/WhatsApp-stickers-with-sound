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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
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
    var numberText by remember { mutableStateOf(WhatsAppLauncher.getSelfNumber(context)) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val fileCallback = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val handler = remember { Handler(Looper.getMainLooper()) }

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
            handler.postDelayed({ attemptInject(webViewHolder, handler, 25) }, 900L)
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

    // Trigger immediate injection when returning to an already-loaded WebView with a pending sticker
    LaunchedEffect(Unit) {
        if (WebViewHolder.hasFinishedFirstLoad && WebSendBridge.pendingStickerBase64 != null) {
            handler.postDelayed({ attemptInject(webViewHolder, handler, 25) }, 350L)
        }
    }

    // Detach only on tab leave without destroying WebView so WhatsApp Web does not reload
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
                onClick = { attemptInject(webViewHolder, handler, 20) },
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
                        WebSendBridge.navigatedToSelf = false
                        WebViewHolder.hasFinishedFirstLoad = false
                        isLoading = true
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = numberText,
                onValueChange = { numberText = it },
                label = { Text("رقمك للواتساب (مع رمز الدولة)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f).testTag("web_self_number_input")
            )
            Button(
                onClick = {
                    WhatsAppLauncher.setSelfNumber(context, numberText)
                    WebSendBridge.selfNumber = numberText.filter { it.isDigit() }
                },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("web_save_number_button")
            ) {
                Text("حفظ")
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
 * Smart injection: if a chat composer is already open, paste and send immediately with zero navigation.
 * Only if no composer is present and selfNumber is saved, navigate to send?phone= once.
 */
private fun attemptInject(
    holder: Array<WebView?>,
    handler: Handler,
    retries: Int
) {
    val wv = holder[0] ?: return
    val b64 = WebSendBridge.pendingStickerBase64 ?: return
    if (retries <= 0) return

    val probeJs =
        "(function(){return !!document.querySelector('#main footer div[contenteditable=\"true\"]');})()"
    wv.evaluateJavascript(probeJs) { hasBox ->
        if (hasBox == "true") {
            wv.evaluateJavascript(injectJs(b64)) { res ->
                if (res?.contains("PASTED") == true) {
                    handler.postDelayed({
                        if (WebSendBridge.autoSend) {
                            wv.evaluateJavascript(sendJs()) {}
                        }
                        WebSendBridge.pendingStickerBase64 = null
                        WebSendBridge.navigatedToSelf = false
                    }, 1100L)
                } else {
                    handler.postDelayed({ attemptInject(holder, handler, retries - 1) }, 700L)
                }
            }
        } else {
            val num = WebSendBridge.selfNumber
            if (num.isNotBlank() && !WebSendBridge.navigatedToSelf) {
                WebSendBridge.navigatedToSelf = true
                wv.loadUrl("https://web.whatsapp.com/send?phone=$num")
                handler.postDelayed({ attemptInject(holder, handler, retries - 1) }, 1500L)
            } else {
                handler.postDelayed({ attemptInject(holder, handler, retries - 1) }, 700L)
            }
        }
    }
}

private fun injectJs(b64: String) = """
    (function(){try{
      var bin=atob("$b64");var arr=new Uint8Array(bin.length);
      for(var i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);
      var file=new File([arr],"sticker.webp",{type:"image/webp"});
      var dt=new DataTransfer();dt.items.add(file);
      var box=document.querySelector('#main footer div[contenteditable="true"][data-tab="10"]')
            ||document.querySelector('#main footer div[contenteditable="true"]');
      if(!box)return "NO_BOX";
      box.focus();
      box.dispatchEvent(new ClipboardEvent("paste",{clipboardData:dt,bubbles:true,cancelable:true}));
      return "PASTED";
    }catch(e){return "ERR:"+e.message;}})();
""".trimIndent()

private fun sendJs() = """
    (function(){
      var icons=document.querySelectorAll('[data-icon="wds-ic-send-filled"],[data-icon="send"]');
      var el=null;
      for(var i=icons.length-1;i>=0;i--){var r=icons[i].getBoundingClientRect();
        if(r.width>0&&r.height>0){el=icons[i];break;}}
      if(!el)return "NO_SEND";
      var btn=el.closest('button')||el.closest('[role="button"]')||el.parentElement;
      (btn||el).click();return "SENT";
    })();
""".trimIndent()
