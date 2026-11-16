pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Build tooling (AGP, lint, KSP, the test runners) pulls these in transitively at
// versions with published advisories. None of them ships in the APK. A lower request
// is raised to the first fixed release and newer ones are left alone, on every project
// configuration and build-script classpath. The BouncyCastle modules move together.
val securityFloors =
    mapOf(
        "com.google.guava:guava" to "33.7.2", // GHSA-xxph-c9ww-hj94
        "org.bouncycastle:bcprov-jdk18on" to "1.85", // GHSA-9pwp-9qqc-pr26
        "org.bouncycastle:bcpkix-jdk18on" to "1.85",
        "org.bouncycastle:bcutil-jdk18on" to "1.85",
        "org.jsoup:jsoup" to "1.23.2", // GHSA-65r4-943x-97jj
    )

fun numericVersion(version: String): List<Int>? = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

fun isBelowFloor(
    version: String,
    floor: String,
): Boolean {
    val have = numericVersion(version) ?: return false
    val want = numericVersion(floor) ?: return false
    for (i in 0 until maxOf(have.size, want.size)) {
        val a = have.getOrElse(i) { 0 }
        val b = want.getOrElse(i) { 0 }
        if (a != b) return a < b
    }
    return false
}

gradle.allprojects {
    val raiseToFloor =
        Action<DependencyResolveDetails> {
            val floor = securityFloors["${requested.group}:${requested.name}"] ?: return@Action
            val version = requested.version ?: return@Action
            if (!isBelowFloor(version, floor)) return@Action
            // Guava publishes -jre and -android flavors of each release; keep the one asked for.
            val flavor = if (requested.name != "guava") "" else if (version.endsWith("-android")) "-android" else "-jre"
            useVersion(floor + flavor)
            because("security floor for a build-tool dependency")
        }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raiseToFloor) }
    configurations.configureEach { resolutionStrategy.eachDependency(raiseToFloor) }
}

rootProject.name = "CallShield"
include(":app")
