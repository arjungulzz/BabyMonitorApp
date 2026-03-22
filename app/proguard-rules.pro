# Added NanoHTTPD rules to prevent runtime R8/Proguard crashes
-keep class fi.iki.elonen.** { *; }

# Prevent stripping of your Activity classes used in Intents
-keep public class apadev232228.babymonitor.** extends android.app.Activity
-keep public class apadev232228.babymonitor.** extends android.app.Service

# Google Play Billing rules
-keep class com.android.vending.billing.**
-keep class com.google.android.gms.ads.** { *; }

# Keep line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*