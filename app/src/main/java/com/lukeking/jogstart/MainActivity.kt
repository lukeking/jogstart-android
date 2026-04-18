package com.lukeking.jogstart

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lukeking.jogstart.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── URL of the hosted web app ──────────────────────────────
    // Change this if you move the page to a different URL.
    private val WEB_APP_URL = "https://lukeking.github.io/jog-detector/jog-detector.html"

    private val YTM_PACKAGE = "com.google.android.apps.youtube.music"
    private val YTM_FALLBACK = "https://music.youtube.com/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on at the OS level — belt-and-suspenders alongside
        // the web layer's WakeLock API.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView(binding.webView)
        binding.webView.loadUrl(WEB_APP_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage for session history
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false  // allow Web Audio autoplay
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = webView.settings.userAgentString + " JogStartApp/1.0"
        }

        // ── JavaScript → Android bridge ────────────────────────
        // Exposed as window.Android in the web page.
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        // ── Chrome client: grant sensor + audio permissions ────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Grant accelerometer (device motion) and audio capture
                // so the web app never has to ask the user again.
                request.grant(request.resources)
            }
        }

        // ── WebView client: keep navigation inside the app ─────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                // Intent URIs from the page (e.g. intent://...) — let the
                // bridge handle these rather than WebView swallowing them.
                if (url.startsWith("intent://")) {
                    handleIntentUri(url)
                    return true
                }
                // Keep all github.io navigation inside the WebView.
                if (url.contains("lukeking.github.io")) return false
                // Everything else (external links) — open in system browser.
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        // Enable WebView debugging in debug builds (useful with Chrome DevTools)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    // ── JavascriptInterface ────────────────────────────────────
    // Methods here are callable from JS as: Android.methodName(args)
    // They run on a background thread — post to main thread for UI/intents.
    inner class AndroidBridge {

        /**
         * Called by the web app when jogging is detected.
         * Launches YouTube Music native app directly via startActivity().
         * No user gesture required — this is native Android code.
         */
        @JavascriptInterface
        fun launchYouTubeMusic() {
            runOnUiThread {
                openYouTubeMusic()
            }
        }

        /**
         * Returns "true" if YouTube Music is installed, "false" otherwise.
         * The web app can call this to decide whether to show the intent
         * banner or a "install YouTube Music" prompt.
         */
        @JavascriptInterface
        fun isYouTubeMusicInstalled(): String {
            return try {
                packageManager.getPackageInfo(YTM_PACKAGE, 0)
                "true"
            } catch (e: PackageManager.NameNotFoundException) {
                "false"
            }
        }

        /**
         * Lets the web app log a message to Android's Logcat.
         * Visible in Android Studio's Logcat filtered by tag "JogStart".
         */
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("JogStart", "[WebApp] $message")
        }
    }

    // ── Open YouTube Music ─────────────────────────────────────
    private fun openYouTubeMusic() {
        // Strategy 1: launch the app directly by package name.
        // This is instant, no disambiguation dialog, no gesture needed.
        val launchIntent = packageManager.getLaunchIntentForPackage(YTM_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            android.util.Log.d("JogStart", "YouTube Music launched via getLaunchIntent")
            return
        }

        // Strategy 2: implicit intent with music.youtube.com URL.
        // Android will offer YouTube Music if installed.
        try {
            val uri = Uri.parse(YTM_FALLBACK)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            android.util.Log.d("JogStart", "YouTube Music launched via ACTION_VIEW")
        } catch (e: ActivityNotFoundException) {
            // Strategy 3: open Play Store page for YouTube Music.
            try {
                val playIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$YTM_PACKAGE")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(playIntent)
            } catch (e2: ActivityNotFoundException) {
                android.util.Log.e("JogStart", "Could not open YouTube Music or Play Store")
            }
        }
    }

    // ── Handle intent:// URIs navigated from the WebView ──────
    // (Handles manual banner taps that still use intent:// href)
    private fun handleIntentUri(intentUri: String) {
        try {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fall back to opening YTM directly
            openYouTubeMusic()
        }
    }

    override fun onBackPressed() {
        // Let the WebView handle back navigation (e.g. closing help sheet)
        // before exiting the activity.
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
