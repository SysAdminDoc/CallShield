param(
    [string]$ApkPath = ""
)

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $releaseDirectory = "app\build\outputs\apk\release"
    $metadataPath = Join-Path $releaseDirectory "output-metadata.json"
    if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
        $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        $metadataCandidate = Join-Path $releaseDirectory $metadata.elements[0].outputFile
        if (Test-Path -LiteralPath $metadataCandidate -PathType Leaf) {
            $ApkPath = $metadataCandidate
        }
    }
    if ([string]::IsNullOrWhiteSpace($ApkPath)) {
        $ApkPath = @(
            (Join-Path $releaseDirectory "app-release.apk"),
            (Join-Path $releaseDirectory "app-release-unsigned.apk")
        ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($ApkPath)) {
        throw "No release APK found under $releaseDirectory. Run assembleRelease first."
    }
}

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop
$hashPath = "$($resolvedApk.Path).sha256"
$hash = Get-FileHash -LiteralPath $resolvedApk.Path -Algorithm SHA256
$fileName = Split-Path -Leaf $resolvedApk.Path

"$($hash.Hash.ToLowerInvariant())  $fileName" | Set-Content -LiteralPath $hashPath -Encoding ASCII -NoNewline
Write-Output $hashPath
