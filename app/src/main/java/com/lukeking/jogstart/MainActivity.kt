package com.lukeking.jogstart

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lukeking.jogstart.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val TAG = "JogStart"
    private val WEB_APP_URL = "https://lukeking.github.io/jog-detector/index.html"
    private val YTM_PACKAGE = "com.google.android.apps.youtube.music"

    // YouTube Music's MediaBrowserService — the same service Google Assistant
    // connects to when you say "Hey Google, play running music on YouTube Music".
    private val YTM_SERVICE =
        "com.google.android.apps.youtube.music.playback.MediaBrowserMusicService"

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var pendingQuery: String? = null

    // ── MediaBrowser connection lifecycle ──────────────────────
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected to YouTube Music")
            try {
                val token = mediaBrowser?.sessionToken ?: return
                mediaController = MediaControllerCompat(this@MainActivity, token)
                pendingQuery?.let { q -> triggerPlayback(q); pendingQuery = null }
            } catch (e: RemoteException) {
                Log.e(TAG, "MediaController error: ${e.message}")
            }
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "MediaBrowser suspended")
            mediaController = null
        }

        override fun onConnectionFailed() {
            // YTM service name may have changed in an update — fall back gracefully
            Log.w(TAG, "MediaBrowser failed — falling back to plain launch")
            mediaController = null
            pendingQuery?.let { fallbackLaunch(); pendingQuery = null }
        }
    }

    // ── Start playback via MediaController.transportControls ───
    private fun triggerPlayback(query: String) {
        val ctrl = mediaController
        if (ctrl == null) {
            pendingQuery = query
            connectMediaBrowser()
            return
        }
        try {
            // playFromSearch with empty string = "smart default" (last playlist / recommended)
            // playFromSearch with a query = search YTM library and play immediately
            ctrl.transportControls.playFromSearch(query.ifBlank { "" }, Bundle.EMPTY)
            Log.d(TAG, "playFromSearch('$query') sent")
        } catch (e: Exception) {
            Log.e(TAG, "playFromSearch threw: ${e.message}")
            fallbackLaunch()
        }
    }

    // ── Connect to YTM's background media service ──────────────
    private fun connectMediaBrowser() {
        if (mediaBrowser?.isConnected == true) return
        try {
            mediaBrowser?.disconnect()
            mediaBrowser = MediaBrowserCompat(
                this,
                ComponentName(YTM_PACKAGE, YTM_SERVICE),
                connectionCallbacks,
                null
            )
            mediaBrowser?.connect()
            Log.d(TAG, "MediaBrowser connecting…")
        } catch (e: Exception) {
            Log.e(TAG, "MediaBrowser connect threw: ${e.message}")
            fallbackLaunch()
        }
    }

    // ── Fallback: just open YTM (no auto-play guarantee) ───────
    private fun fallbackLaunch() {
        try {
            val i = packageManager.getLaunchIntentForPackage(YTM_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$YTM_PACKAGE"))
                        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot open YTM or Play Store")
            }
        }
    }

    private fun isYtmInstalled() = try {
        packageManager.getPackageInfo(YTM_PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    // ══════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView(binding.webView)
        binding.webView.loadUrl(WEB_APP_URL)

        // Pre-connect on app start so the browser is ready when jogging fires.
        // This means zero delay between detection and playback starting.
        if (isYtmInstalled()) connectMediaBrowser()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaBrowser?.disconnect()
        mediaBrowser = null
        mediaController = null
    }

    // ── WebView ────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = webView.settings.userAgentString + " JogStartApp/1.0"
        }

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("intent://")) { handleIntentUri(url); return true }
                if (url.contains("lukeking.github.io")) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    // ── JS ↔ Kotlin bridge ─────────────────────────────────────
    inner class AndroidBridge {

        /**
         * Called by JS when jogging is detected.
         * Connects to YouTube Music's MediaBrowserService and calls
         * playFromSearch() — this starts playback with no user tap needed.
         */
        @JavascriptInterface
        fun launchYouTubeMusic(query: String = "") {
            runOnUiThread {
                Log.d(TAG, "launchYouTubeMusic('$query')")
                if (!isYtmInstalled()) { fallbackLaunch(); return@runOnUiThread }

                if (mediaBrowser?.isConnected == true && mediaController != null) {
                    triggerPlayback(query)
                } else {
                    pendingQuery = query
                    connectMediaBrowser()
                }
            }
        }

        @JavascriptInterface
        fun isYouTubeMusicInstalled(): String = if (isYtmInstalled()) "true" else "false"

        @JavascriptInterface
        fun log(message: String) { Log.d(TAG, "[JS] $message") }
    }

    private fun handleIntentUri(intentUri: String) {
        try {
            startActivity(
                Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (e: Exception) { fallbackLaunch() }
    }
}
