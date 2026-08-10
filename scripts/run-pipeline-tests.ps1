<#
.SYNOPSIS
    Runs the JavaScript (Cloudflare Worker) and Python data-pipeline tests.

.DESCRIPTION
    The phone-number normalizer is implemented three times — Kotlin on device,
    JavaScript in the report worker, and Python in the merge pipeline — and the
    three must agree. Only the Kotlin side is covered by the Gradle test suite,
    so these tests are the sole guard on the other two. Before this script they
    ran only when someone remembered to invoke node/python by hand, which is
    how the worker shipped a normalizer that rewrote international reports into
    fabricated US numbers while every gated test stayed green.

    Node and Python are optional: if a runtime is missing the script reports it
    and skips that half rather than failing, so the Gradle `check` task still
    works on a machine without them.

.EXAMPLE
    pwsh -File scripts/run-pipeline-tests.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$failures = @()
$ran = 0

function Get-Tool {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        $found = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    return $null
}

$node = Get-Tool @('node')
if ($node) {
    # Pass the test files explicitly. `node --test <dir>` reports a spurious
    # top-level failure for the directory itself on the pinned Node version.
    $workerTests = @(Get-ChildItem -Path (Join-Path $repoRoot 'worker') -Filter '*.test.mjs' -File)
    foreach ($workerTest in $workerTests) {
        Write-Host "Running $($workerTest.Name)..."
        & $node --test $workerTest.FullName
        if ($LASTEXITCODE -ne 0) { $failures += $workerTest.Name }
        $ran++
    }
    if ($workerTests.Count -eq 0) { Write-Warning 'No worker *.test.mjs files found.' }
} else {
    Write-Warning 'node not found on PATH - skipping Cloudflare Worker tests.'
}

# Prefer a direct interpreter over the `py` launcher: py.exe blocks when run
# from a non-interactive shell with no console attached (CI-style invocation).
$python = Get-Tool @('python3', 'python')
if ($python) {
    foreach ($test in @('test_phone_normalization.py', 'test_report_dedup.py', 'test_report_pipeline.py', 'test_model_calibration.py', 'test_check_translations.py', 'test_source_registry.py', 'test_spam_shards.py')) {
        $path = Join-Path $PSScriptRoot $test
        if (-not (Test-Path $path)) { continue }
        Write-Host "Running $test..."
        & $python $path
        if ($LASTEXITCODE -ne 0) { $failures += $test }
        $ran++
    }
} else {
    Write-Warning 'python not found on PATH - skipping Python pipeline tests.'
}

# Translation resources are contributed by people who cannot run the Android
# build, and a format-specifier mismatch only throws when the string is shown.
# Checked here so it is gated by `check` rather than by review attention.
if ($python) {
    $translationChecker = Join-Path $PSScriptRoot 'check_translations.py'
    if (Test-Path $translationChecker) {
        Write-Host 'Running check_translations.py...'
        & $python $translationChecker
        if ($LASTEXITCODE -ne 0) { $failures += 'check_translations.py' }
        $ran++
    }
}

if ($ran -eq 0) {
    Write-Warning 'No pipeline tests were run (neither node nor python available).'
    exit 0
}

if ($failures.Count -gt 0) {
    Write-Error ("Pipeline tests failed: {0}" -f ($failures -join ', '))
    exit 1
}

Write-Host "All $ran pipeline test suite(s) passed."
