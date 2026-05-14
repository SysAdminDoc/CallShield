param(
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk"
)

$resolvedApk = Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop
$hashPath = "$($resolvedApk.Path).sha256"
$hash = Get-FileHash -LiteralPath $resolvedApk.Path -Algorithm SHA256
$fileName = Split-Path -Leaf $resolvedApk.Path

"$($hash.Hash.ToLowerInvariant())  $fileName" | Set-Content -LiteralPath $hashPath -Encoding ASCII -NoNewline
Write-Output $hashPath
