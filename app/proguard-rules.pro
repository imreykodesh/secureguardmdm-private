# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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

# Gson constructs these models reflectively and maps JSON by field name.
# Keep the classes and their fields when the opt-in shrinking candidate is built.
-keepattributes Signature
-keep class com.secureguard.mdm.utils.update.UpdateManifest { *; }
-keep class com.secureguard.mdm.ministore.data.MiniStoreCatalogEnvelope { *; }
-keep class com.secureguard.mdm.ministore.data.MiniStoreCatalogPayload { *; }
-keep class com.secureguard.mdm.ministore.data.MiniStoreCatalogApp { *; }
-keep class com.secureguard.mdm.ministore.data.MiniStoreCatalogClient$PublicKeyConfig { *; }

# SLF4J supports an optional runtime binding; this app intentionally ships none.
-dontwarn org.slf4j.impl.StaticLoggerBinder
