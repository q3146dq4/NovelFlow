# WebView JavaScript Interface
-keepclassmembers class com.NovelRegEx.app.activity.MainActivity$ScrollRestoreInterface {
  @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.NovelRegEx.app.activity.MainActivity$FilterCssInterface {
  @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.NovelRegEx.app.activity.MainActivity$TtsJavascriptInterface {
  @android.webkit.JavascriptInterface <methods>;
}
# Preferences
-keep class androidx.preference.** { *; }
# Keep R8 from breaking coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
