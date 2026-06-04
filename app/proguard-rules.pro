# Keep the JavaScript bridge
-keepclassmembers class com.hypeai.app.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
