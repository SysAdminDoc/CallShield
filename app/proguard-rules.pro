-keepattributes *Annotation*
-keep class com.sysadmindoc.callshield.data.model.** { *; }
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
