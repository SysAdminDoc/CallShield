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
# Backup payload data classes are all serialized via KotlinJsonAdapterFactory
# (pure reflection, no @Json annotations). Keep the whole family — enumerating
# them individually already dropped BackupSettings/BackupRangeRule/BackupLogEntry
# once, which strips Kotlin metadata and either crashes the adapter or writes
# obfuscated keys that restore silently loses.
-keep class com.sysadmindoc.callshield.data.BackupRestore$* { *; }

# GitHubDataSource JSON models parsed by Moshi
-keep class com.sysadmindoc.callshield.data.model.SpamDatabase { *; }
-keep class com.sysadmindoc.callshield.data.model.SpamNumberJson { *; }
-keep class com.sysadmindoc.callshield.data.model.SpamPrefixJson { *; }
-keep class com.sysadmindoc.callshield.data.model.HotNumber { *; }

# GitHubDataSource private payload inner data classes parsed by Moshi reflection.
# Without these, R8 strips Kotlin metadata and Moshi's ClassJsonAdapter throws
# "Cannot serialize abstract class" at GitHubDataSource.<init>, crashing MainViewModel
# construction on app launch.
-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource$HotListPayload { *; }
-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource$HotListEntry { *; }
-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource$HotRangesPayload { *; }
-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource$HotRangeEntry { *; }
-keep class com.sysadmindoc.callshield.data.remote.GitHubDataSource$SpamDomainsPayload { *; }

# Preserve Kotlin metadata + generic signatures so KotlinJsonAdapterFactory can
# introspect data classes after R8 optimization.
-keepattributes RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

# Campaign detection singleton
-keep class com.sysadmindoc.callshield.data.CampaignDetector { *; }

# GBT model tree data class (parsed dynamically)
-keep class com.sysadmindoc.callshield.data.SpamMLScorer$GbtTree { *; }

# Keep services and receivers registered in manifest
-keep class com.sysadmindoc.callshield.service.** { *; }
-keep class com.sysadmindoc.callshield.ui.widget.** { *; }
