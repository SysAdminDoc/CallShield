<#
.SYNOPSIS
    Fails when a release APK is debug-signed or signed with multiple certificates.

.DESCRIPTION
    Debug-signed or multi-certificate release APKs break signing-key continuity
    (can't co-install with a real-key build), are rejected by Accrescent, and
    fail Google Developer Verification's single-stable-key prerequisite. This
    gate turns those silent distribution blockers into a build-time error.

    It runs `apksigner verify --print-certs` and asserts:
      1. exactly one signer certificate is present, and
      2. the certificate is NOT the Android debug certificate
         (DN "CN=Android Debug, O=Android, C=US").

.PARAMETER ApkPath
    Path to the release APK to verify. Defaults to the AGP release output.

.PARAMETER SdkDir
    Android SDK root. Defaults to ANDROID_HOME, then sdk.dir in local.properties.

.EXAMPLE
    pwsh scripts/verify-release-signing.ps1 -ApkPath CallShield-v1.7.19.apk
#>
param(
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",
    [string]$SdkDir = ""
)

$ErrorActionPreference = "Stop"

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

# Match both apksigner output dialects:
#   build-tools <=36:  "Signer #1 certificate DN: <dn>"
#   build-tools >=37:  "V3.0 Signer: certificate DN: <dn>"  (one line per signature scheme)
# Deduplicate: a single cert signed with v1+v2+v3 emits the same DN several times;
# that is ONE signer. Distinct DNs mean a genuine multi-certificate APK.
$dns = @($out |
    Where-Object { $_ -match 'certificate DN:' } |
    ForEach-Object { ($_ -replace '^.*?certificate DN:\s*', '').Trim() } |
    Select-Object -Unique)

if ($dns.Count -eq 0) {
    Write-Error "No signer certificate found. A release APK must be signed with a real release key."
    exit 1
}

if ($dns.Count -gt 1) {
    Write-Error ("Release APK is signed with $($dns.Count) certificates; exactly one is required " +
        "(multi-cert makes key rotation impossible and is rejected by Accrescent):`n  " +
        ($dns -join "`n  "))
    exit 1
}

$dn = $dns[0]
if ($dn -match 'CN=Android Debug' -or $dn -match 'O=Android Debug') {
    Write-Error ("Release APK is DEBUG-signed (DN: $dn). Debug releases break key continuity, " +
        "can't co-install with a real-key build, are rejected by Accrescent, and fail Google " +
        "Developer Verification. Configure RELEASE_STORE_* in local.properties and rebuild.")
    exit 1
}

Write-Output "OK: single non-debug signer certificate."
Write-Output "  DN: $dn"
exit 0
