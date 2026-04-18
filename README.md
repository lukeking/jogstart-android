# JogStart

JogStart is an Android application that acts as a native wrapper for the Jog Detector web application. It uses a WebView to host the web app and provides a JavaScript bridge to trigger native Android actions.

## Features

- **Web App Integration**: Loads the [Jog Detector](https://lukeking.github.io/jog-detector/jog-detector.html) web application.
- **Motion Detection**: Leverages the web app's motion detection capabilities to detect when the user starts jogging.
- **Native Bridge**: Provides a `JavascriptInterface` (`window.Android`) that allows the web app to:
    - Launch the YouTube Music app directly.
    - Check if YouTube Music is installed.
    - Log messages to Android's Logcat.
- **Keep Screen On**: Automatically keeps the device screen on while the app is active to ensure continuous detection.
- **Permissions**: Automatically handles sensor and audio permissions within the WebView.

## How it works

The app loads the Jog Detector URL. When the web app's algorithms detect a jogging motion, it calls `window.Android.launchYouTubeMusic()`. The native Android code then attempts to launch the YouTube Music app directly, or falls back to the Play Store if it's not installed.

## Requirements

- Android 5.0+ (API level 21+)
- Internet connection (to load the web app)
- YouTube Music app (recommended for the full experience)

## Development

- Built with Kotlin and Jetpack Compose (where applicable).
- Uses View Binding for UI interaction.
- Configured for AndroidX and Jetifier.
