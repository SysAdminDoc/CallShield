import java.util.zip.ZipFile

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
        ZipFile(apk.asFile).use { zip ->
            check(zip.getEntry("META-INF/version-control-info.textproto") == null) {
                "Release APK must not contain META-INF/version-control-info.textproto; " +
                    "disable android.vcsInfo.include for reproducible releases."
            }
        }
    }
}
