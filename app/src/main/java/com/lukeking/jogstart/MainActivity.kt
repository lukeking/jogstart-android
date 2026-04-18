package com.lukeking.jogstart

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
         * Launches YouTube Music and starts playback immediately.
         * @param query Optional search query e.g. "running music".
         *              Pass empty string to play the smart default (last playlist).
         */
        @JavascriptInterface
        fun launchYouTubeMusic(query: String = "") {
            runOnUiThread {
                openYouTubeMusic(query)
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

    // ── Open YouTube Music and START PLAYBACK IMMEDIATELY ─────
    //
    // Strategy 1 — MEDIA_PLAY_FROM_SEARCH targeted at YouTube Music.
    //   This is the standard Android inter-app "play music now" intent.
    //   With an empty query and no EXTRA_MEDIA_FOCUS, YouTube Music
    //   plays a smart default: the last playlist/mix the user listened to.
    //   You can also pass a query like "running music" or "energetic" to
    //   search YTM's library and play matching results immediately.
    //
    // Strategy 2 — getLaunchIntentForPackage fallback.
    //   If YTM doesn't handle the search intent (shouldn't happen but safe),
    //   just open the app and let it resume whatever was playing.
    //
    // Strategy 3 — Play Store, if YTM isn't installed at all.
    //
    private fun openYouTubeMusic(searchQuery: String = "") {
        // ── Strategy 1: play-from-search ──────────────────────
        try {
            val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                // Target YouTube Music specifically — without this it might
                // open a different music app if one is also installed.
                setPackage(YTM_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                if (searchQuery.isNotBlank()) {
                    // Play something matching the query (e.g. "running music")
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, searchQuery)
                } else {
                    // Empty query = "play something smart" — YTM picks the
                    // last/recommended playlist automatically.
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, "")
                }
            }

            // Check if YTM can handle this intent before firing
            val resolves = packageManager.queryIntentActivities(searchIntent, 0)
            if (resolves.isNotEmpty()) {
                startActivity(searchIntent)
                android.util.Log.d("JogStart", "YTM launched via MEDIA_PLAY_FROM_SEARCH (query='$searchQuery')")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("JogStart", "play-from-search failed: ${e.message}")
        }

        // ── Strategy 2: plain launch (resumes last session) ───
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(YTM_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                android.util.Log.d("JogStart", "YTM launched via getLaunchIntent (no auto-play)")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("JogStart", "getLaunchIntent failed: ${e.message}")
        }

        // ── Strategy 3: open Play Store ───────────────────────
        try {
            val storeIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$YTM_PACKAGE")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(storeIntent)
        } catch (e: ActivityNotFoundException) {
            android.util.Log.e("JogStart", "Could not open Play Store either")
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
