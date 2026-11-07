# Xenon — ProGuard / R8 rules.
# The app uses no reflection except the standard Android framework callbacks,
# so default Android optimisations are sufficient.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebView JavaScript callbacks (defensive: kept in case a JS bridge is added later).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
