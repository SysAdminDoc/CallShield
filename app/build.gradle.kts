import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

val localProperties =
    Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }

fun signingProp(key: String): String? = localProperties.getProperty(key) ?: System.getenv(key)

val stageBundledAssets =
    tasks.register<Sync>("stageBundledAssets") {
        from(rootProject.file("data")) {
            include(
                "spam_numbers.json",
                "hot_numbers.json",
                "hot_ranges.json",
                "spam_domains.json",
                "spam_model_weights.json",
            )
        }
        into(layout.buildDirectory.dir("generated/callshieldAssets"))
    }

android {
    namespace = "com.sysadmindoc.callshield"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sysadmindoc.callshield"
        minSdk = 29
        targetSdk = 36
        versionCode = 55
        versionName = "1.7.27"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = signingProp("RELEASE_STORE_FILE")
    val releaseStorePassword = signingProp("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingProp("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingProp("RELEASE_KEY_PASSWORD")

    if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            signingConfig =
                if (listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // The repository data directory also contains raw community
            // submissions and maintainer notes. Stage an explicit runtime
            // allowlist instead of recursively packaging that directory.
            assets.srcDir(layout.buildDirectory.dir("generated/callshieldAssets"))
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs merged Android resources/manifest to run the
            // real framework classes off-device. returnDefaultValues stays
            // OFF intentionally — it masks real bugs; Robolectric shadows
            // provide genuine framework behavior instead.
            isIncludeAndroidResources = true

            all {
                // The wall-clock ceilings in HotPathBenchmarkTest and RaceTest
                // were read from this property but nothing ever set it, so a
                // loaded machine (parallel builds are routine here) could flake
                // them. Raise it with -PbenchHeadroom=3 on a slow or busy host.
                it.systemProperty(
                    "callshield.benchHeadroom",
                    (project.findProperty("benchHeadroom") as String?) ?: "1.0",
                )
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

hilt {
    enableAggregatingTask = true
}

kover {
    reports {
        variant("debug") {
            filters {
                includes {
                    classes(
                        "com.sysadmindoc.callshield.data.*",
                        "com.sysadmindoc.callshield.util.*",
                        "com.sysadmindoc.callshield.permissions.*",
                        "com.sysadmindoc.callshield.service.*",
                    )
                }
                excludes {
                    // Exclude Room-generated artifacts and DAOs (no JVM-test
                    // coverage by design), but NOT the whole package: it also
                    // holds AppDatabase's corruption detection and recovery,
                    // which sits on the screening path and does have JVM tests
                    // (AppDatabaseCorruptionTest). Excluding it wholesale let
                    // that logic lose its tests without the gate noticing.
                    classes(
                        "com.sysadmindoc.callshield.data.local.*_Impl",
                        "com.sysadmindoc.callshield.data.local.*Dao*",
                    )
                }
            }
            verify {
                rule("debug line coverage") {
                    minBound(35)
                }
            }
        }
    }
}

ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}

detekt {
    toolVersion = libs.versions.detekt.get()
    buildUponDefaultConfig = true
    config.setFrom(file("config/detekt/detekt.yml"))
    parallel = true
    ignoreFailures = false
    source.setFrom(
        "src/main/java",
        "src/test/java",
        "src/androidTest/java",
    )
    basePath = rootDir.absolutePath
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
    }
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyDebugApkPrivacy"))
    dependsOn(rootProject.tasks.named("verifyBackupPrivacyRules"))
    dependsOn(rootProject.tasks.named("verifyPipelineTests"))
}

tasks.named("preBuild") {
    dependsOn(stageBundledAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    debugImplementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumentation tests (emulator / device)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
