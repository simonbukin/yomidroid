# Add project specific ProGuard rules here.
# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }

# Keep Room entities
-keep class com.yomidroid.data.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
