import java.util.zip.ZipFile

val bundledRuntimeAssets =
    setOf(
        "assets/spam_numbers.json",
        "assets/hot_numbers.json",
        "assets/hot_ranges.json",
        "assets/spam_domains.json",
        "assets/spam_model_weights.json",
    )

fun verifyApkDataPrivacy(apkFile: File) {
    val dataRoot = layout.projectDirectory.dir("data").asFile
    val repositoryDataAssets =
        dataRoot
            .walkTopDown()
            .filter { it.isFile }
            .map { "assets/${it.relativeTo(dataRoot).invariantSeparatorsPath}" }
            .toSet()

    ZipFile(apkFile).use { zip ->
        val packagedAssets =
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("assets/") && !it.endsWith("/") }
                .toSet()
        val missing = bundledRuntimeAssets - packagedAssets
        val leaked = (packagedAssets intersect repositoryDataAssets) - bundledRuntimeAssets

        check(missing.isEmpty()) {
            "APK is missing required bundled feeds: ${missing.sorted().joinToString()}"
        }
        check(leaked.isEmpty()) {
            "APK contains non-runtime repository data: ${leaked.sorted().joinToString()}"
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("verifyReproducibleBuildInputs") {
    group = "verification"
    description = "Fails when Gradle build scripts embed wall-clock build metadata."

    val scanTargets = files(
        "build.gradle.kts",
        "settings.gradle.kts",
        "app/build.gradle.kts",
        "gradle/libs.versions.toml"
    )
    inputs.files(scanTargets)

    doLast {
        val forbiddenTokens = listOf(
            "System.current" + "TimeMillis",
            "Instant" + ".now",
            "LocalDate" + ".now",
            "LocalDateTime" + ".now",
            "OffsetDateTime" + ".now",
            "ZonedDateTime" + ".now",
            "Date" + "()",
            "BUILD" + "_TIME",
            "BUILD" + "_TIMESTAMP",
            "build" + "_time",
            "build" + "_timestamp"
        )

        val hits = scanTargets.files
            .filter { it.isFile }
            .flatMap { file ->
                val text = file.readText()
                forbiddenTokens
                    .filter { token -> text.contains(token) }
                    .map { token -> "${file.relativeTo(rootDir).invariantSeparatorsPath}: $token" }
            }

        check(hits.isEmpty()) {
            "Build scripts must not embed wall-clock metadata into the APK:\n${hits.joinToString("\n")}"
        }
    }
}

tasks.register("verifyReleaseApkReproducibleMetadata") {
    group = "verification"
    description = "Fails when the release APK contains AGP VCS metadata."
    dependsOn(":app:assembleRelease")

    val apk = layout.projectDirectory.file("app/build/outputs/apk/release/app-release.apk")
    inputs.file(apk)

    doLast {
        verifyApkDataPrivacy(apk.asFile)
        ZipFile(apk.asFile).use { zip ->
            check(zip.getEntry("META-INF/version-control-info.textproto") == null) {
                "Release APK must not contain META-INF/version-control-info.textproto; " +
                    "disable android.vcsInfo.include for reproducible releases."
            }
        }
    }
}

tasks.register("verifyDebugApkPrivacy") {
    group = "verification"
    description = "Fails when the debug APK leaks non-runtime repository data."
    dependsOn(":app:assembleDebug")

    val apk = layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")
    inputs.file(apk)

    doLast {
        verifyApkDataPrivacy(apk.asFile)
    }
}

tasks.register("verifyBackupPrivacyRules") {
    group = "verification"
    description = "Fails when Android cloud backup includes the sensitive Room database."

    val legacyRules = layout.projectDirectory.file("app/src/main/res/xml/backup_rules.xml")
    val modernRules = layout.projectDirectory.file("app/src/main/res/xml/data_extraction_rules.xml")
    inputs.files(legacyRules, modernRules)

    doLast {
        val legacy = legacyRules.asFile.readText()
        val modern = modernRules.asFile.readText()
        val cloudRules = modern.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val deviceTransferRules = modern.substringAfter("<device-transfer>").substringBefore("</device-transfer>")

        check("domain=\"database\"" !in legacy) {
            "API 23-30 cloud backup must not include the sensitive Room database."
        }
        check("domain=\"database\"" !in cloudRules) {
            "API 31+ cloud backup must not include the sensitive Room database."
        }
        check("domain=\"database\"" in deviceTransferRules) {
            "Direct device transfer should preserve the Room database."
        }
    }
}
