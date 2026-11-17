package com.simplecalculator.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Sites whose player only exists behind JavaScript are shown here: an isolated
 * WebView that blocks ad/tracker hosts and refuses to navigate off-site.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPlayerScreen(url: String, title: String, onBack: () -> Unit) {
    val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
    var reloadKey by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            factory = { context ->
                var retried = false
                WebView(context).apply {
                    // Sites reject the stock WebView UA, which renders the plain
                    // "broken robot" error page instead of the player.
                    settings.userAgentString = WEB_UA
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadsImagesAutomatically = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Pop-unders and external intents are what "throw you out".
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message
                        ): Boolean = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            failed = view.url?.startsWith("about:") == true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            if (request.isForMainFrame) {
                                failed = true
                                // One automatic retry; a persistent failure must
                                // not turn into a reload loop.
                                if (!retried) {
                                    retried = true
                                    view.postDelayed({ view.reload() }, 400)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val requestHost = request.url.host ?: return null
                            if (AdBlock.blocked(requestHost)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val scheme = request.url.scheme.orEmpty()
                            if (scheme != "http" && scheme != "https") return true
                            val target = request.url.host ?: return true
                            // Exact host or a subdomain only; suffix matching in
                            // either direction also lets a parent domain through.
                            return !allowedHost(target, host)
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { view ->
                if (view.tag != reloadKey) {
                    view.tag = reloadKey
                    if (reloadKey > 0) view.reload()
                }
            }
        )
        if (failed) {
            Box(
                Modifier.fillMaxWidth().padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "This source would not load in the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        failed = false
                        reloadKey += 1
                    }) { Text("Reload") }
                }
            }
        }
    }
}

private const val WEB_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

private val embedHosts = setOf(
    "drive.google.com",
    "v.aki-h.com",
    "streaming.aki.today",
    "aki-h.stream",
    "player.marimo.me",
    "lulustream.com",
    "lplaygo.com",
    "streamhide.com",
    "doodstream.com",
    "dood.wf",
    "vidoza.net",
    "upstream.to",
    "ds2play.com",
    "playerwish.com",
    "javstream.com",
    "streamtape.com",
    "mixdrop.co",
    "filemoon",
    "voe.sx",
    "sibnet.ru",
    "ok.ru",
    "avgle.net"
)

private fun allowedHost(target: String, host: String): Boolean {
    if (host.isEmpty()) return false
    val t = target.lowercase()
    val h = host.lowercase()
    return t == h || t.endsWith(".$h") ||
        embedHosts.any { t == it || t.endsWith(".$it") }
}

object AdBlock {
    private val fragments = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "google-analytics",
        "googletagmanager", "adservice", "adsystem", "adnxs", "rubiconproject",
        "pubmatic", "criteo", "taboola", "outbrain", "popads", "popcash", "exoclick",
        "exosrv", "trafficjunky", "trafficfactory", "juicyads", "jads.co",
        "propellerads", "propeller", "onclickads", "adcash", "hilltopads",
        "smartfloralab", "sadbaguette", "zedo", "mgid", "revcontent", "adsterra",
        "clickadu", "pushhouse", "notix", "histats", "statcounter", "yandex"
    )

    fun blocked(host: String): Boolean {
        val h = host.lowercase()
        return fragments.any { h.contains(it) }
    }
}
