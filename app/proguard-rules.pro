# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── FPS Stats: Keep Gson-serialized data classes ──
# Gson uses reflection to map JSON keys to field names.
# R8 would obfuscate these fields, causing silent data loss.
-keep class com.ireddragonicy.gamespace.data.fpsstats.PerformanceSample { *; }
-keep class com.ireddragonicy.gamespace.data.fpsstats.ThreadInfo { *; }
-keep class com.ireddragonicy.gamespace.data.fpsstats.ThreadSnapshot { *; }
-keep class com.ireddragonicy.gamespace.data.fpsstats.SessionSummary { *; }
-keep class com.ireddragonicy.gamespace.data.fpsstats.FpsStatsSession { *; }
-keep class com.ireddragonicy.gamespace.data.fpsstats.SessionListItem { *; }

# Preserve generic type signatures — Gson needs these to deserialize
# List<PerformanceSample>, List<ThreadSnapshot>, etc. inside data classes.
-keepattributes Signature
-keepattributes *Annotation*

