# F-Droid Submission Prep

This repo now carries the upstream metadata needed before opening an F-Droid
`fdroiddata` merge request.

## Upstream Metadata

- App ID: `com.sysadmindoc.callshield`
- License: MIT
- Source: `https://github.com/SysAdminDoc/CallShield`
- Latest release prepared for verification: `v1.7.12`
- Version code: `40`
- Release APK: `https://github.com/SysAdminDoc/CallShield/releases/download/v1.7.12/CallShield-v1.7.12.apk`
- APK SHA256: *(generate with `scripts/write-release-sha256.ps1` after signing)*
- Signer SHA256: `920e583ae6ce9f3863a6b3b8847e927d53a66c38a245e12e30ce124c9f4a75f5`
  (rotated 2026-08-24: the previous release key's credentials were lost in a
  machine rebuild, so releases from v1.7.37 on use a new key; installs signed
  by the old key must be uninstalled before upgrading)

## Files To Copy Into fdroiddata

- Draft app metadata: `docs/fdroid/com.sysadmindoc.callshield.yml`
- Localized Fastlane listing: `fastlane/metadata/android/en-US/`

The draft uses F-Droid's reproducible-build `Binaries` flow so F-Droid can
verify its rebuild against the upstream signed APK before publishing the
developer-signed binary.

## Local Preflight

```powershell
.\gradlew.bat --no-daemon verifyReproducibleBuildInputs verifyReleaseApkReproducibleMetadata :app:testDebugUnitTest :app:lintDebug
.\scripts\write-release-sha256.ps1 -ApkPath .\CallShield-v1.7.12.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --verbose --print-certs .\CallShield-v1.7.12.apk
```

Expected signer:

```text
920e583ae6ce9f3863a6b3b8847e927d53a66c38a245e12e30ce124c9f4a75f5
```

## Remaining External Steps

1. Fork `https://gitlab.com/fdroid/fdroiddata`.
2. Add `metadata/com.sysadmindoc.callshield.yml` from the draft in this repo.
3. Copy the Fastlane listing if reviewers request upstream localized metadata.
4. Run `fdroid rewritemeta com.sysadmindoc.callshield`.
5. Run `fdroid lint com.sysadmindoc.callshield`.
6. Run an fdroidserver build for version code 40.
7. Run F-Droid signature-copy verification against the upstream APK.
8. Open the GitLab merge request and include the release URL, signer SHA256,
   APK SHA256, and the local verification command output.

The actual merge request and final apksigcopier/fdroidserver result must happen
in a local fdroiddata checkout or GitLab CI environment.
