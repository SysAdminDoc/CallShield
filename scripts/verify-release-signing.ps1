<#
.SYNOPSIS
    Fails unless a release APK has exactly the pinned signer certificate.

.DESCRIPTION
    Unpinned or multi-certificate release APKs break signing-key continuity
    (can't co-install with a real-key build), are rejected by Accrescent, and
    fail Google Developer Verification's single-stable-key prerequisite. This
    gate turns those silent distribution blockers into a build-time error.

    It runs `apksigner verify --print-certs` and asserts:
      1. exactly one signer certificate is present, and
      2. its SHA-256 digest matches the pinned CallShield release certificate.

.PARAMETER ApkPath
    Path to the release APK to verify. Defaults to the AGP release output.

.PARAMETER SdkDir
    Android SDK root. Defaults to ANDROID_HOME, then sdk.dir in local.properties.

.PARAMETER ExpectedSignerSha256
    Pinned release-certificate SHA-256. Defaults to the published F-Droid pin.

.EXAMPLE
    pwsh scripts/verify-release-signing.ps1 -ApkPath app\build\outputs\apk\release\app-release.apk
#>
param(
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",
    [string]$SdkDir = "",
    [string]$ExpectedSignerSha256 = "d179d0daa9eac6b52fc19d3a7126fd6ccb911923a43a3cf0bef9f74b12234ad2"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "release-signing-policy.ps1")

function Resolve-SdkDir {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path (Get-Location) "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            # local.properties escapes backslashes and colons (sdk.dir=C\:\\Users\\...)
            return ($line -replace '^\s*sdk\.dir\s*=', '') -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
    throw "Cannot locate the Android SDK. Set ANDROID_HOME or pass -SdkDir."
}

function Find-ApkSigner {
    param([string]$Sdk)
    $buildTools = Join-Path $Sdk "build-tools"
    if (-not (Test-Path $buildTools)) { throw "No build-tools under $Sdk" }
    # Highest build-tools version wins.
    $dir = Get-ChildItem -Directory $buildTools |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -ErrorAction SilentlyContinue |
        Select-Object -Last 1
    if (-not $dir) { throw "No build-tools versions under $buildTools" }
    $exe = Join-Path $dir.FullName "apksigner.bat"
    if (-not (Test-Path $exe)) { $exe = Join-Path $dir.FullName "apksigner" }
    if (-not (Test-Path $exe)) { throw "apksigner not found in $($dir.FullName)" }
    return $exe
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$sdk = Resolve-SdkDir -Explicit $SdkDir
$apksigner = Find-ApkSigner -Sdk $sdk

Write-Output "Verifying signer certificate of: $resolvedApk"
# Coerce every record (native stderr merged by 2>&1 arrives as ErrorRecord) to a string.
$out = @(& $apksigner verify --print-certs $resolvedApk 2>&1 | ForEach-Object { [string]$_ })
$exit = $LASTEXITCODE
if ($exit -ne 0) {
    Write-Error "apksigner could not verify the APK (is it signed?):`n$($out -join "`n")"
    exit 1
}

try {
    $signer = Assert-ReleaseSignerPolicy `
        -CertificateOutput $out `
        -ExpectedSignerSha256 $ExpectedSignerSha256
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

Write-Output "OK: single pinned release signer certificate."
Write-Output "  DN: $($signer.DistinguishedName)"
Write-Output "  SHA-256: $($signer.Sha256)"
exit 0
