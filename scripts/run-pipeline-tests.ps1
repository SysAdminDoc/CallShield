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

.PARAMETER CorrectnessOnly
    Skip the check against the live report queue. The validation workflow runs
    on every push and must be green on a healthy tree; queue liveness has its
    own weekly workflow, which tracks a stall as a single issue instead.

.EXAMPLE
    pwsh -File scripts/run-pipeline-tests.ps1
#>

[CmdletBinding()]
param(
    [switch]$CorrectnessOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$failures = @()
$ran = 0

# A suite that writes into the real data/ directory changes what the next one
# reads, and .gitignore can hide it: a test merge rewrote the ignored
# data/source-snapshot.json on every run, and the release-drift test passed in
# CI only because of that leak. Hashing catches rewrites git status cannot see.
function Get-DataFingerprint {
    $dataDir = Join-Path $repoRoot 'data'
    Get-ChildItem -LiteralPath $dataDir -File -Recurse | ForEach-Object {
        "{0}`t{1}" -f $_.FullName.Substring($dataDir.Length + 1), (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
}
$dataBefore = @(Get-DataFingerprint)

function Get-Tool {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        $found = Get-Command $candidate -ErrorAction SilentlyContinue
        # Windows ships App Execution Alias stubs (python.exe/python3.exe under
        # WindowsApps) that print a Store install hint and exit non-zero. They
        # satisfy Get-Command, which made every Python suite "fail" instead of
        # running. Treat a stub as not-found so the next candidate is tried.
        if ($found -and $found.Source -and $found.Source -notmatch '\\Microsoft\\WindowsApps\\') {
            return $found.Source
        }
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
    foreach ($test in @('test_phone_normalization.py', 'test_report_dedup.py', 'test_report_pipeline.py', 'test_pipeline_liveness.py', 'test_model_calibration.py', 'test_ml_feature_contract.py', 'test_release_sbom.py', 'test_check_translations.py', 'test_source_registry.py', 'test_spam_shards.py', 'test_incremental_sources.py', 'test_regional_prefixes.py', 'test_release_drift.py', 'test_check_live_pins.py', 'test_probe_live_sources.py')) {
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

# The suites above all answer questions about a queue they were handed. None of
# them notices when the real queue stops being consumed, which is how 267 report
# files accumulated while every gated test stayed green. This runs the same
# checks against the live `data/reports/`.
if ($python -and -not $CorrectnessOnly) {
    $livenessCheck = Join-Path $PSScriptRoot 'pipeline_liveness.py'
    if (Test-Path $livenessCheck) {
        Write-Host 'Running pipeline_liveness.py (live report queue)...'
        & $python $livenessCheck
        if ($LASTEXITCODE -ne 0) { $failures += 'pipeline_liveness.py (live queue)' }
        $ran++
    }
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

$dataChanges = @(
    Compare-Object -ReferenceObject $dataBefore -DifferenceObject @(Get-DataFingerprint) |
        ForEach-Object { ($_.InputObject -split "`t")[0] } |
        Sort-Object -Unique
)
if ($dataChanges.Count -gt 0) {
    $failures += "a suite wrote into data/ ($($dataChanges -join ', '))"
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
