-keepattributes *Annotation*

# Room entities and DAOs
-keep class com.sysadmindoc.callshield.data.model.** { *; }
-keep class com.sysadmindoc.callshield.data.local.** { *; }

# Moshi JSON serialization
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep class kotlin.reflect.jvm.internal.** { *; }

# BlocklistExporter data classes used by Moshi reflection
-keep class com.sysadmindoc.callshield.data.BlocklistExporter$ExportData { *; }
-keep class com.sysadmindoc.callshield.data.BlocklistExporter$ExportNumber { *; }
-keep class com.sysadmindoc.callshield.data.BackupRestore$Backup { *; }
-keep class com.sysadmindoc.callshield.data.BackupRestore$BackupNumber { *; }
-keep class com.sysadmindoc.callshield.data.BackupRestore$BackupWhitelist { *; }
-keep class com.sysadmindoc.callshield.data.BackupRestore$BackupWildcard { *; }
-keep class com.sysadmindoc.callshield.data.BackupRestore$BackupKeyword { *; }

# GitHubDataSource JSON models parsed by Moshi
-keep class com.sysadmindoc.callshield.data.model.SpamDatabase { *; }
-keep class com.sysadmindoc.callshield.data.model.SpamNumberJson { *; }
-keep class com.sysadmindoc.callshield.data.model.SpamPrefixJson { *; }
-keep class com.sysadmindoc.callshield.data.model.HotNumber { *; }

# Keep services and receivers registered in manifest
-keep class com.sysadmindoc.callshield.service.** { *; }
-keep class com.sysadmindoc.callshield.ui.widget.** { *; }
