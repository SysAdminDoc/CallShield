import java.util.zip.ZipFile

data class AppReleaseVersion(
    val name: String,
    val code: Int,
)

fun parseAppReleaseVersion(buildFile: File): AppReleaseVersion {
    val text = buildFile.readText()
    val name =
        Regex("""(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: error("app/build.gradle.kts must declare versionName")
    val code =
        Regex("""(?m)^\s*versionCode\s*=\s*(\d+)\s*$""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("app/build.gradle.kts must declare versionCode")
    return AppReleaseVersion(name, code)
}

fun metadataValue(
    text: String,
    key: String,
): String? =
    Regex("""(?m)^${Regex.escape(key)}:\s*(\S+)\s*$""")
        .find(text)
        ?.groupValues
        ?.get(1)

val appReleaseVersion = parseAppReleaseVersion(file("app/build.gradle.kts"))

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
    dependsOn("verifyReleaseMetadata", ":app:assembleRelease")

    val releaseOutput = layout.projectDirectory.dir("app/build/outputs/apk/release")
    inputs.dir(releaseOutput)

    doLast {
        val apks =
            releaseOutput.asFile
                .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
                .orEmpty()
                .toList()
        check(apks.size == 1) {
            "Expected exactly one release APK, found ${apks.map { it.name }.sorted()}"
        }
        val apk = apks.single()
        verifyApkDataPrivacy(apk)
        ZipFile(apk).use { zip ->
            check(zip.getEntry("META-INF/version-control-info.textproto") == null) {
                "Release APK must not contain META-INF/version-control-info.textproto; " +
                    "disable android.vcsInfo.include for reproducible releases."
            }
        }
    }
}

tasks.register("verifyReleaseMetadata") {
    group = "verification"
    description = "Fails when release, store, README, or F-Droid metadata drifts from the app."

    val readme = layout.projectDirectory.file("README.md")
    val storeDescription = layout.projectDirectory.file("fastlane/metadata/android/en-US/full_description.txt")
    val storeShortDescription = layout.projectDirectory.file("fastlane/metadata/android/en-US/short_description.txt")
    val storeChangelog =
        layout.projectDirectory.file(
            "fastlane/metadata/android/en-US/changelogs/${appReleaseVersion.code}.txt",
        )
    val fdroidMetadata = layout.projectDirectory.file("docs/fdroid/com.sysadmindoc.callshield.yml")
    val fdroidRunbook = layout.projectDirectory.file("docs/fdroid-submission.md")
    val signingPreflight = layout.projectDirectory.file("scripts/verify-release-signing.ps1")
    inputs.files(
        "app/build.gradle.kts",
        readme,
        storeDescription,
        storeShortDescription,
        storeChangelog,
        fdroidMetadata,
        fdroidRunbook,
        signingPreflight,
    )

    doLast {
        val issues = mutableListOf<String>()
        val readmeText = readme.asFile.readText()
        val fullDescription = storeDescription.asFile.readText()
        val shortDescription = storeShortDescription.asFile.readText()
        val fdroidText = fdroidMetadata.asFile.readText()
        val runbookText = fdroidRunbook.asFile.readText()
        val signingPreflightText = signingPreflight.asFile.readText()

        if ("## v${appReleaseVersion.name} Highlights" !in readmeText) {
            issues += "README highlights do not match app version ${appReleaseVersion.name}."
        }
        if ("## Detection Pipeline (v${appReleaseVersion.name})" !in readmeText) {
            issues += "README detection-pipeline version does not match ${appReleaseVersion.name}."
        }
        if ("img.shields.io/github/v/release/SysAdminDoc/CallShield" !in readmeText) {
            issues += "README release badge must follow the latest GitHub release."
        }

        val testCount =
            Regex("""\*\*(\d+) total JVM unit tests\*\*""")
                .find(readmeText)
                ?.groupValues
                ?.get(1)
        if (testCount == null) {
            issues += "README must declare the current JVM test count."
        } else {
            val testCountReferences =
                listOf(
                    "img.shields.io/badge/Tests-$testCount-",
                    "alt=\"$testCount Tests\"",
                    "# $testCount tests",
                    "| Tests | $testCount JVM unit tests (JUnit) |",
                )
            if (testCountReferences.any { it !in readmeText }) {
                issues += "README test badge, command, summary, and table must all use $testCount."
            }
        }

        if (!storeChangelog.asFile.isFile || storeChangelog.asFile.readText().isBlank()) {
            issues += "Fastlane changelog ${appReleaseVersion.code}.txt is missing or empty."
        } else if (storeChangelog.asFile.readText().length > 500) {
            issues += "Fastlane changelog ${appReleaseVersion.code}.txt exceeds 500 characters."
        }

        val currentStoreCopy = "$fullDescription\n$shortDescription\n$fdroidText".lowercase()
        val retiredClaims =
            listOf(
                "abstractapi",
                "optional api key",
                "optional caller enrichment key",
                "enrichment key stored",
            )
        retiredClaims.filter(currentStoreCopy::contains).forEach { claim ->
            issues += "Current store metadata still contains retired claim: $claim."
        }
        if (Regex("""CallShield-v\d+\.\d+\.\d+\.apk""").containsMatchIn(signingPreflightText)) {
            issues += "Signing preflight examples must use the stable AGP release output path."
        }

        val preparedVersion = metadataValue(fdroidText, "CurrentVersion")
        val preparedCode = metadataValue(fdroidText, "CurrentVersionCode")
        val lastBuildVersion =
            Regex("""(?m)^\s*-\s+versionName:\s*(\S+)\s*$""")
                .findAll(fdroidText)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        val lastBuildCode =
            Regex("""(?m)^\s+versionCode:\s*(\d+)\s*$""")
                .findAll(fdroidText)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        if (preparedVersion == null || preparedCode == null) {
            issues += "F-Droid metadata must declare its last externally prepared build."
        } else {
            if (preparedVersion != lastBuildVersion || preparedCode != lastBuildCode) {
                issues += "F-Droid CurrentVersion must match its last prepared Builds entry."
            }
            if ("Last externally prepared build: $preparedVersion ($preparedCode)." !in fdroidText) {
                issues += "F-Droid metadata must label its last externally prepared build."
            }
            val currentSourceStatus =
                "Current app source: ${appReleaseVersion.name} (${appReleaseVersion.code}); " +
                    "no matching F-Droid build has been prepared."
            if (currentSourceStatus !in fdroidText) {
                issues += "F-Droid metadata must distinguish the current app source version."
            }
            if ("Latest release prepared for verification: `v$preparedVersion`" !in runbookText ||
                "Version code: `$preparedCode`" !in runbookText
            ) {
                issues += "F-Droid runbook does not match the prepared metadata build."
            }
        }

        check(issues.isEmpty()) {
            "Release metadata verification failed:\n${issues.joinToString("\n") { "- $it" }}"
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
