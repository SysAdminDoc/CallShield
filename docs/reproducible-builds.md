# Reproducible Build Runbook

CallShield's release path is designed to keep build inputs reviewable,
repeatable, and honest about which artifact is being compared. GitHub Release
SHA256 sidecars prove download integrity for the exact signed APK. Rebuild
verification should compare unsigned APK content, ZIP entries, or F-Droid-style
signature-copied APKs rather than assuming a locally signed APK will always have
the same raw bytes.

## Fixed Inputs

- Gradle wrapper: `gradle/wrapper/gradle-wrapper.properties`
- Android Gradle Plugin, Kotlin, KSP, Room, OkHttp, and AndroidX versions:
  `gradle/libs.versions.toml`
- Dependency graph: checked-in Gradle lockfiles generated with
  `./gradlew --write-locks`
- Java runtime: JDK 17
- Android SDK: compile/target SDK 36
- Signing: local release keystore values supplied through `local.properties` or
  environment variables

The app does not define `buildConfigField`, `resValue`, or manifest placeholders
that embed wall-clock build time. Runtime timestamps in crash reports, exports,
call logs, and sync metadata are produced only while the installed app runs and
do not affect APK bytes.

Release builds also disable Android Gradle Plugin VCS metadata
(`META-INF/version-control-info.textproto`) because it depends on the Git
revision and dirty-tree state rather than app runtime behavior.

## Local Release Build

Use PowerShell on Windows:

```powershell
.\gradlew.bat --no-daemon verifyReproducibleBuildInputs :app:assembleRelease
.\gradlew.bat --no-daemon verifyReleaseApkReproducibleMetadata
.\scripts\verify-release-signing.ps1
.\scripts\write-release-sha256.ps1
Get-Content .\app\build\outputs\apk\release\app-release.apk.sha256
```

`verify-release-signing.ps1` is a mandatory release gate. It fails the release
if the APK is **debug-signed** or carries **more than one signer certificate**.
Debug or multi-certificate releases break signing-key continuity (they cannot
co-install with a real-key build), are rejected by Accrescent, and fail Google
Developer Verification's single-stable-key requirement. When `RELEASE_STORE_*`
is absent from `local.properties`, `assembleRelease` produces an *unsigned* APK
(then debug-signed for local testing) — this gate is what stops such a build
from being shipped as a release.

The signed APK is written to:

```text
app\build\outputs\apk\release\app-release.apk
```

The sidecar hash is written to:

```text
app\build\outputs\apk\release\app-release.apk.sha256
```

That sidecar belongs to the exact signed file produced in that release build.
It is an integrity check for Obtainium and direct-download users, not by itself
a proof that a separately signed rebuild has identical raw bytes.

## Independent Hash Check

1. Download the release APK and `.sha256` sidecar.
2. Verify download integrity:

```powershell
Get-FileHash .\app-release.apk -Algorithm SHA256
Get-Content .\app-release.apk.sha256
```

3. Check out the same release tag.
4. Use the same JDK 17, Android SDK 36, Gradle wrapper, and dependency
   lockfiles.
5. Build:

```powershell
.\gradlew.bat --no-daemon --offline verifyReproducibleBuildInputs verifyReleaseApkReproducibleMetadata
.\scripts\write-release-sha256.ps1
```

6. Compare APK ZIP entry content:

```powershell
.\scripts\compare-apk-contents.ps1 -ReferenceApk .\app-release.apk -CandidateApk .\app\build\outputs\apk\release\app-release.apk
```

Unsigned CI artifacts are useful for dependency and source reproducibility, but
they are not byte-identical to the locally signed release APK because the CI
runner does not have the release keystore. Even two local signed builds can
differ in bytes outside ZIP entries when the APK Signature Scheme v2 signing
block changes; F-Droid-style reproducible verification handles this with
signature copying before comparing the resulting APK.

For the current F-Droid submission draft, release signer fingerprint, and
fdroidserver handoff steps, see `docs/fdroid-submission.md`.

## Accrescent Packaging (Developer Verification survival path)

Google's Developer Verification (2026-09-30 in BR/ID/SG/TH, global through 2027)
blocks sideloaded APKs on certified devices from unverified developers, and
F-Droid has stated it cannot comply. Accrescent — which self-registered in the
verification program and is a GrapheneOS-default store — accepts a
developer-signed bundletool `.apks` split set and is the viable survival path.

Build and validate an Accrescent-ready split set:

```powershell
# bundletool >= 1.11.4 (default lookup: $env:BUNDLETOOL_JAR, then ~/repos/bundletool.jar)
.\scripts\build-accrescent-apks.ps1 `
    -KeystorePath callshield-release.jks `
    -KeystorePassword $env:RELEASE_STORE_PASSWORD `
    -KeyAlias callshield `
    -KeyPassword $env:RELEASE_KEY_PASSWORD
```

The script runs `:app:bundleRelease`, generates a signed `.apks` via bundletool,
and enforces Accrescent's documented requirements: single **non-debug** signer
certificate, `.apks` ≤ 150 MiB, bundletool ≥ 1.11.4. Upload the resulting
`.apks` with the 512×512 icon at `app/src/main/ic_launcher-playstore.png`.

Requirements not covered by the script (operator-gated): the release must be
signed with the real `callshield-release.jks` (Accrescent rejects debug certs —
see `verify-release-signing.ps1`), and publishing requires developer-identity
registration on the Accrescent console. A dry-run with a throwaway key validates
the pipeline structurally but must not be published.

## Updating Locks

When dependency versions change intentionally:

```powershell
.\gradlew.bat --no-daemon --write-locks verifyReproducibleBuildInputs :app:testDebugUnitTest :app:assembleRelease
```

Review and commit the changed lockfiles with the dependency update. Do not run
`--write-locks` as part of normal CI.
