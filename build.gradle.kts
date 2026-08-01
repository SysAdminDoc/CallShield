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

data class SigningSecretFinding(
    val path: String,
    val line: Int,
    val kind: String,
)

fun isExternalSecretReference(value: String): Boolean {
    val normalized = value.trim()
    val interpolation = Regex("""\$\{[^}]*}""")
    val lookupPrefixes =
        listOf(
            "System.getenv(",
            "signingProp(",
            "findProperty(",
            "project.findProperty(",
            "rootProject.findProperty(",
            "providers.",
            "property(",
        )
    return normalized.isBlank() ||
        normalized in setOf("...", "<redacted>", "<secret>", "<password>") ||
        interpolation.containsMatchIn(normalized) ||
        lookupPrefixes.any(normalized::startsWith)
}

fun findSigningSecretFindings(
    path: String,
    text: String,
): List<SigningSecretFinding> {
    val privateKeyHeader =
        Regex("""-----BEGIN\s+(?:RSA\s+|EC\s+|OPENSSH\s+)?PRIVATE KEY-----""", RegexOption.IGNORE_CASE)
    val quotedPasswordAssignment =
        Regex(
            """\b(?:storePassword|keyPassword|keystorePassword)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
    val namedPasswordAssignment =
        Regex(
            """^\s*["']?(?:RELEASE_(?:STORE|KEY)_PASSWORD|STORE_PASSWORD|KEY_PASSWORD)["']?\s*[=:]\s*(.+?)\s*$""",
            RegexOption.IGNORE_CASE,
        )

    return buildList {
        text.lineSequence().forEachIndexed { index, line ->
            if (privateKeyHeader.containsMatchIn(line)) {
                add(SigningSecretFinding(path, index + 1, "private-key material"))
            }
            quotedPasswordAssignment.findAll(line).forEach { match ->
                if (!isExternalSecretReference(match.groupValues[1])) {
                    add(SigningSecretFinding(path, index + 1, "literal signing password"))
                }
            }
            namedPasswordAssignment.find(line)?.let { match ->
                val value =
                    match.groupValues[1]
                        .trim()
                        .removeSuffix(",")
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                if (!isExternalSecretReference(value)) {
                    add(SigningSecretFinding(path, index + 1, "literal credential property"))
                }
            }
        }
    }.distinct()
}

fun requireNoTrackedSigningSecrets(findings: List<SigningSecretFinding>) {
    check(findings.isEmpty()) {
        "Tracked signing-secret preflight failed:\n" +
            findings.joinToString("\n") { finding ->
                "- ${finding.path}:${finding.line}: ${finding.kind}"
            }
    }
}

private val signingKeyContainerExtensions = setOf("jks", "keystore", "p12", "pfx")

/**
 * Decode only files that look like UTF-8 text. Every tracked file reaches
 * this content sniff; binary assets are skipped because password assignments
 * and PEM headers cannot be meaningfully searched in arbitrary binary data.
 */
fun readTextForSigningSecretScan(file: File): String? {
    val bytes = file.readBytes()
    if (bytes.isEmpty()) return ""
    val sample = bytes.take(8_192)
    if (sample.any { byte -> byte == 0.toByte() }) return null
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
}

fun scanTrackedSigningFiles(
    files: List<File>,
    relativeTo: File,
): List<SigningSecretFinding> =
    files.flatMap { file ->
        if (!file.isFile) {
            emptyList()
        } else {
            val path = file.relativeTo(relativeTo).invariantSeparatorsPath
            if (file.extension.lowercase() in signingKeyContainerExtensions) {
                listOf(SigningSecretFinding(path, 1, "signing key container"))
            } else {
                readTextForSigningSecretScan(file)
                    ?.let { text -> findSigningSecretFindings(path, text) }
                    .orEmpty()
            }
        }
    }

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
    dependsOn("verifyTrackedSigningSecrets")

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
    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    val changelogScreen =
        layout.projectDirectory.file(
            "app/src/main/java/com/sysadmindoc/callshield/ui/screens/more/ChangelogScreen.kt",
        )
    inputs.files(
        "app/build.gradle.kts",
        readme,
        storeDescription,
        storeShortDescription,
        storeChangelog,
        fdroidMetadata,
        fdroidRunbook,
        signingPreflight,
        changelog,
        changelogScreen,
    )

    doLast {
        val issues = mutableListOf<String>()
        val readmeText = readme.asFile.readText()
        val fullDescription = storeDescription.asFile.readText()
        val shortDescription = storeShortDescription.asFile.readText()
        val fdroidText = fdroidMetadata.asFile.readText()
        val runbookText = fdroidRunbook.asFile.readText()
        val signingPreflightText = signingPreflight.asFile.readText()
        val changelogText = changelog.asFile.readText()
        val changelogScreenText = changelogScreen.asFile.readText()

        if ("## v${appReleaseVersion.name}" !in changelogText) {
            issues += "CHANGELOG.md has no section for v${appReleaseVersion.name}."
        }
        if (Regex("""## \[?Unreleased""").findAll(changelogText).count() > 1) {
            issues += "CHANGELOG.md has more than one Unreleased section."
        }
        if ("\"${appReleaseVersion.name}\"" !in changelogScreenText) {
            issues += "In-app ChangelogScreen has no entry for v${appReleaseVersion.name}."
        }
        if ("isLatest = true" !in changelogScreenText) {
            issues += "In-app ChangelogScreen must flag a latest version entry."
        }

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

tasks.register("verifySigningSecretGuardTests") {
    group = "verification"
    description = "Proves literal signing secrets fail without appearing in diagnostics."
    val accrescentScript = layout.projectDirectory.file("scripts/build-accrescent-apks.ps1")
    inputs.file(accrescentScript)

    doLast {
        val sentinel = "synthetic-secret-must-not-print"
        val syntheticSecrets =
            listOf(
                "fixture.gradle.kts" to ("store" + "Password = \"$sentinel\""),
                "fixture.properties" to ("RELEASE_STORE_" + "PASSWORD=$sentinel"),
                "fixture.pem" to ("-----BEGIN " + "PRIVATE KEY-----\n$sentinel"),
                "extensionless" to ("RELEASE_KEY_" + "PASSWORD=p@${'$'}${'$'}word"),
            )
        val fixtureRoot = temporaryDir.resolve("tracked-secret-fixtures").apply { mkdirs() }
        val fixtureFiles =
            syntheticSecrets.map { (path, text) ->
                fixtureRoot.resolve(path).apply { writeText(text) }
            }
        val binaryKeyContainer = fixtureRoot.resolve("fixture.p12").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        val findings = scanTrackedSigningFiles(fixtureFiles + binaryKeyContainer, fixtureRoot)
        val failure = runCatching { requireNoTrackedSigningSecrets(findings) }.exceptionOrNull()
        check(failure != null) { "Synthetic signing-secret fixture was not rejected." }
        check(sentinel !in failure.message.orEmpty()) { "Signing-secret diagnostic exposed the matched value." }
        check(findings.map(SigningSecretFinding::path).containsAll(syntheticSecrets.map { it.first } + "fixture.p12")) {
            "Every tracked-file fixture must pass through the production scanner and be rejected."
        }

        val safeLookups =
            """
            storePassword = releaseStorePassword
            keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            RELEASE_STORE_PASSWORD: ${'$'}{{ secrets.RELEASE_STORE_PASSWORD }}
            """.trimIndent()
        check(findSigningSecretFindings("safe.yml", safeLookups).isEmpty()) {
            "Environment and property lookups must remain valid."
        }

        val deceptiveLiterals =
            listOf(
                "RELEASE_STORE_" + "PASSWORD: p@${'$'}${'$'}word",
                "RELEASE_KEY_" + "PASSWORD: prefixSystem.getenv(\"RELEASE_KEY_PASSWORD\")",
            ).joinToString("\n")
        check(findSigningSecretFindings("unsafe.properties", deceptiveLiterals).size == 2) {
            "Dollar signs and embedded lookup names must not bypass literal-secret detection."
        }

        val accrescentText = accrescentScript.asFile.readText()
        check(Regex("""(?i)\[securestring]\s*\${'$'}KeystorePassword""").containsMatchIn(accrescentText))
        check(Regex("""(?i)\[securestring]\s*\${'$'}KeyPassword""").containsMatchIn(accrescentText))
        check("SetAccessRuleProtection(${ '$' }true, ${ '$' }false)" in accrescentText)
        check("ZeroFreeBSTR" in accrescentText)
        check("New-TemporaryFile" !in accrescentText && "Set-Content -Path ${ '$' }ksPassFile" !in accrescentText) {
            "Accrescent signing passwords must not use ordinary temporary files."
        }
    }
}

tasks.register("verifyTrackedSigningSecrets") {
    group = "verification"
    description = "Rejects signing secrets embedded in tracked source and configuration files."
    dependsOn("verifySigningSecretGuardTests")

    val trackedSourceFiles =
        providers
            .exec {
                commandLine("git", "ls-files", "-z")
            }.standardOutput
            .asText
            .map { output ->
                output
                    .split('\u0000')
                    .filter(String::isNotBlank)
                    .map(rootProject::file)
            }
    inputs.files(trackedSourceFiles)

    doLast {
        val findings = scanTrackedSigningFiles(trackedSourceFiles.get(), rootDir)
        requireNoTrackedSigningSecrets(findings)
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

tasks.register<Exec>("verifyPipelineTests") {
    group = "verification"
    description =
        "Runs the Cloudflare Worker (node) and Python data-pipeline tests. " +
            "The phone-number normalizer is implemented three times — Kotlin, JS, Python — " +
            "and only the Kotlin side is covered by the Gradle suite."

    val script = layout.projectDirectory.file("scripts/run-pipeline-tests.ps1")
    inputs.file(script)
    inputs.dir(layout.projectDirectory.dir("worker"))
    inputs.dir(layout.projectDirectory.dir("scripts"))

    // Skips (with a warning) when node/python are absent, so a machine without
    // them can still run `check`.
    commandLine(
        "pwsh",
        "-NoProfile",
        "-NonInteractive",
        "-File",
        script.asFile.absolutePath,
    )
}

tasks.register<Exec>("verifyReleaseSigningPolicyTests") {
    group = "verification"
    description = "Proves the release gate accepts only the pinned signer certificate."

    val policy = layout.projectDirectory.file("scripts/release-signing-policy.ps1")
    val test = layout.projectDirectory.file("scripts/test-release-signing-policy.ps1")
    inputs.files(policy, test)
    commandLine("pwsh", "-NoProfile", "-NonInteractive", "-File", test.asFile.absolutePath)
}
