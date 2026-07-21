<#
.SYNOPSIS
    Builds and validates an Accrescent-ready `.apks` split set from the release
    app bundle.

.DESCRIPTION
    Accrescent (the developer-verification-registered store — the distribution
    path F-Droid's build-and-sign model cannot offer under Google's 2026
    Developer Verification) accepts a developer-signed bundletool `.apks` set,
    not a universal APK. This script:

      1. builds the release App Bundle (`bundleRelease`),
      2. runs bundletool `build-apks` to produce a signed `.apks` split set, and
      3. validates it against Accrescent's documented requirements:
         single non-debug signer certificate, `.apks` <= 150 MiB, bundletool
         >= 1.11.4.

    The final submission MUST be signed with the real release key
    (`callshield-release.jks`); pass its credentials via the parameters below.
    For a pipeline dry-run without the release key, generate a throwaway key
    first — the artifact will validate structurally but must NOT be published.

    Actual submission (developer identity registration on the Accrescent
    console) is an operator decision and is out of scope for this script.

.PARAMETER KeystorePath
    Release keystore. REQUIRED — Accrescent rejects debug-signed APKs.
.PARAMETER KeystorePassword
.PARAMETER KeyAlias
.PARAMETER KeyPassword
.PARAMETER BundletoolJar
    Path to bundletool-all >= 1.11.4. Defaults to $env:BUNDLETOOL_JAR, then
    ~/repos/bundletool.jar.
.PARAMETER OutputApks
    Destination `.apks` path.

.EXAMPLE
    pwsh scripts/build-accrescent-apks.ps1 -KeystorePath callshield-release.jks `
        -KeystorePassword $env:RELEASE_STORE_PASSWORD -KeyAlias callshield `
        -KeyPassword $env:RELEASE_KEY_PASSWORD
#>
param(
    [Parameter(Mandatory = $true)][string]$KeystorePath,
    [Parameter(Mandatory = $true)][string]$KeystorePassword,
    [Parameter(Mandatory = $true)][string]$KeyAlias,
    [Parameter(Mandatory = $true)][string]$KeyPassword,
    [string]$BundletoolJar = "",
    [string]$OutputApks = "app\build\outputs\apks\callshield-release.apks",
    [string]$SdkDir = ""
)

$ErrorActionPreference = "Stop"
$MaxApksBytes = 150 * 1024 * 1024   # Accrescent limit: 150 MiB

function Resolve-Java {
    if ($env:JAVA_HOME) {
        $j = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $j) { return $j }
    }
    return "java"
}

function Resolve-BundletoolJar {
    param([string]$Explicit)
    if ($Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
    if ($env:BUNDLETOOL_JAR -and (Test-Path $env:BUNDLETOOL_JAR)) { return (Resolve-Path $env:BUNDLETOOL_JAR).Path }
    $default = Join-Path $HOME "repos\bundletool.jar"
    if (Test-Path $default) { return (Resolve-Path $default).Path }
    throw "bundletool jar not found. Set BUNDLETOOL_JAR or pass -BundletoolJar."
}

function Resolve-SdkDir {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $lp = Join-Path (Get-Location) "local.properties"
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { return ($line -replace '^\s*sdk\.dir\s*=', '') -replace '\\:', ':' -replace '\\\\', '\' }
    }
    throw "Cannot locate the Android SDK. Set ANDROID_HOME or pass -SdkDir."
}

function Find-Tool {
    param([string]$Sdk, [string]$Name)
    $dir = Get-ChildItem -Directory (Join-Path $Sdk "build-tools") |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -ErrorAction SilentlyContinue |
        Select-Object -Last 1
    $exe = Join-Path $dir.FullName "$Name.bat"
    if (-not (Test-Path $exe)) { $exe = Join-Path $dir.FullName $Name }
    if (-not (Test-Path $exe)) { throw "$Name not found in $($dir.FullName)" }
    return $exe
}

$java = Resolve-Java
$bundletool = Resolve-BundletoolJar -Explicit $BundletoolJar
$sdk = Resolve-SdkDir -Explicit $SdkDir
$apksigner = Find-Tool -Sdk $sdk -Name "apksigner"
$ks = (Resolve-Path -LiteralPath $KeystorePath).Path

# bundletool version gate (Accrescent requires >= 1.11.4)
$btVersion = (& $java -jar $bundletool version 2>&1 | Select-Object -First 1).Trim()
if ([version]$btVersion -lt [version]"1.11.4") {
    Write-Error "bundletool $btVersion is too old; Accrescent requires >= 1.11.4."
    exit 1
}
Write-Output "bundletool $btVersion"

# 1. Build the release App Bundle
Write-Output "Building release App Bundle (bundleRelease)..."
$gradlew = Join-Path (Get-Location) "gradlew.bat"
& $gradlew --no-daemon :app:bundleRelease
if ($LASTEXITCODE -ne 0) { Write-Error "bundleRelease failed."; exit 1 }
$aab = "app\build\outputs\bundle\release\app-release.aab"
if (-not (Test-Path $aab)) { Write-Error "AAB not found at $aab"; exit 1 }

# 2. Generate the signed .apks split set
$outDir = Split-Path -Parent $OutputApks
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Force $outDir | Out-Null }
if (Test-Path $OutputApks) { Remove-Item $OutputApks -Force }
Write-Output "Generating signed .apks split set..."
& $java -jar $bundletool build-apks `
    --bundle=$aab --output=$OutputApks --overwrite `
    --ks=$ks --ks-pass="pass:$KeystorePassword" --ks-key-alias=$KeyAlias --key-pass="pass:$KeyPassword"
if ($LASTEXITCODE -ne 0) { Write-Error "bundletool build-apks failed."; exit 1 }

# 3a. Size gate
$size = (Get-Item $OutputApks).Length
if ($size -gt $MaxApksBytes) {
    Write-Error ("APK set is {0:N0} bytes (> 150 MiB Accrescent limit)." -f $size)
    exit 1
}
Write-Output ("APK set size: {0:N0} bytes (<= 150 MiB)" -f $size)

# 3b. Single non-debug signer gate — extract the base split and inspect its cert
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("cs-apks-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $tmp | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $OutputApks).Path)
    try {
        $baseEntry = $zip.Entries | Where-Object { $_.FullName -match 'base-master\.apk$|(^|/)base\.apk$' } | Select-Object -First 1
        if (-not $baseEntry) { $baseEntry = $zip.Entries | Where-Object { $_.FullName -match '\.apk$' } | Select-Object -First 1 }
        if (-not $baseEntry) { throw "No APK split found inside $OutputApks" }
        $baseApk = Join-Path $tmp "base.apk"
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($baseEntry, $baseApk, $true)
    } finally { $zip.Dispose() }

    $certOut = @(& $apksigner verify --print-certs $baseApk 2>&1 | ForEach-Object { [string]$_ })
    $dns = @($certOut |
        Where-Object { $_ -match 'certificate DN:' } |
        ForEach-Object { ($_ -replace '^.*?certificate DN:\s*', '').Trim() } |
        Select-Object -Unique)
    if ($dns.Count -ne 1) { Write-Error "Split has $($dns.Count) distinct signer certs; exactly one required."; exit 1 }
    if ($dns[0] -match 'CN=Android Debug' -or $dns[0] -match 'O=Android Debug') {
        Write-Error "Split is DEBUG-signed ($($dns[0])). Accrescent rejects debug certs — use the real release key."
        exit 1
    }
    Write-Output "Signer: single non-debug certificate ($($dns[0]))"
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output ""
Write-Output "Accrescent-ready APK set: $OutputApks"
Write-Output "Upload with a 512x512 icon (app/src/main/ic_launcher-playstore.png) via the Accrescent console."
exit 0
