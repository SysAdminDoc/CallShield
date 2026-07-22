param(
    [string]$ApkPath = (Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"),
    [string]$OutputDir = (Join-Path $PSScriptRoot "..\build\pseudolocale-screens")
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$packageName = "com.sysadmindoc.callshield"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "adb not found at $adb"
}
if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Debug APK not found at $ApkPath"
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

function Invoke-Adb {
    param([string[]]$AdbArgs)
    & $adb @AdbArgs
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($AdbArgs -join ' ')"
    }
}

function Set-EmulatorLocale {
    param([string]$LocaleTag)
    Invoke-Adb -AdbArgs @("shell", "cmd", "locale", "set-app-locales", $packageName, "--locales", $LocaleTag)
}

function Clear-AppData {
    foreach ($attempt in 1..5) {
        $clearOutput = (& $adb shell pm clear $packageName 2>&1) -join ""
        if ($LASTEXITCODE -eq 0 -and $clearOutput -match "Success") {
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "Could not clear $packageName after the locale restart"
}

function Wait-ForAppContent {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $windowXml = ""
        try {
            & $adb shell uiautomator dump /sdcard/callshield-window.xml 2>$null | Out-Null
            $windowXml = (& $adb shell cat /sdcard/callshield-window.xml 2>$null) -join ""
        } catch {
            # UIAutomator can briefly report a null root while the activity
            # attaches after a locale restart. Retry until the deadline.
        }
        if ($windowXml.Contains("package=`"$packageName`"") -and $windowXml -match 'text="[^"]+"') {
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "CallShield did not render interactive content within 30 seconds"
}

function Wait-ForWindowText {
    param([string]$ExpectedPattern)
    $deadline = (Get-Date).AddSeconds(15)
    do {
        try {
            & $adb shell uiautomator dump /sdcard/callshield-window.xml 2>$null | Out-Null
            $windowXml = (& $adb shell cat /sdcard/callshield-window.xml 2>$null) -join ""
            if ($windowXml -match $ExpectedPattern) {
                return
            }
        } catch {
            # Retry while Compose settles after navigation.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Expected screen text was not rendered: $ExpectedPattern"
}

function Save-Screenshot {
    param([string]$Name)
    $remotePath = "/sdcard/$Name.png"
    $localPath = Join-Path $resolvedOutput "$Name.png"
    Invoke-Adb -AdbArgs @("shell", "screencap", "-p", $remotePath)
    Invoke-Adb -AdbArgs @("pull", $remotePath, $localPath)
    Invoke-Adb -AdbArgs @("shell", "rm", $remotePath)
}

Invoke-Adb -AdbArgs @("wait-for-device")
Invoke-Adb -AdbArgs @("install", "-r", $ApkPath)

$sizeText = (& $adb shell wm size | Select-String -Pattern "Physical size").Line
if ($sizeText -notmatch "(\d+)x(\d+)") {
    throw "Could not determine emulator display size: $sizeText"
}
$width = [int]$Matches[1]
$height = [int]$Matches[2]
$bottomY = [int]($height * 0.93)
$navY = [int]($height * 0.91)

try {
    foreach ($localeTag in @("en-XA", "ar-XB")) {
        Clear-AppData
        Set-EmulatorLocale $localeTag
        Invoke-Adb -AdbArgs @(
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$packageName/.ui.MainActivity"
        )
        Wait-ForAppContent
        Start-Sleep -Seconds 2

        $slug = $localeTag.ToLowerInvariant()
        $isRtl = $localeTag -eq "ar-XB"
        $nextX = if ($isRtl) { [int]($width * 0.10) } else { [int]($width * 0.90) }
        $blocklistX = if ($isRtl) { [int]($width * 0.25) } else { [int]($width * 0.75) }
        $moreX = if ($isRtl) { [int]($width * 0.075) } else { [int]($width * 0.925) }
        $settingsY = if ($isRtl) { [int]($height * 0.55) } else { [int]($height * 0.61) }
        $settingsScreenPattern = "0[^0-9]*/[^0-9]*2"
        Save-Screenshot "$slug-onboarding"

        foreach ($page in 1..4) {
            Invoke-Adb -AdbArgs @("shell", "input", "tap", $nextX, $bottomY)
            Start-Sleep -Seconds 1
        }
        Start-Sleep -Seconds 4
        Save-Screenshot "$slug-dashboard"

        Invoke-Adb -AdbArgs @("shell", "input", "tap", $blocklistX, $navY)
        Start-Sleep -Seconds 2
        Save-Screenshot "$slug-blocklist"

        Invoke-Adb -AdbArgs @("shell", "input", "tap", $moreX, $navY)
        Start-Sleep -Seconds 2
        Invoke-Adb -AdbArgs @(
            "shell",
            "input",
            "tap",
            [int]($width * 0.50),
            $settingsY
        )
        Wait-ForWindowText $settingsScreenPattern
        Save-Screenshot "$slug-settings"
    }
} finally {
    Invoke-Adb -AdbArgs @("shell", "cmd", "locale", "set-app-locales", $packageName)
}

Write-Host "Pseudolocale screenshots saved to $resolvedOutput"
