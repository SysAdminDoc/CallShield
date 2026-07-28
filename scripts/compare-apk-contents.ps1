param(
    [Parameter(Mandatory = $true)]
    [string]$ReferenceApk,

    [Parameter(Mandatory = $true)]
    [string]$CandidateApk
)

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ZipEntryFingerprint {
    param([string]$Path)

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $zip = [System.IO.Compression.ZipFile]::OpenRead($resolved.Path)

    try {
        foreach ($entry in $zip.Entries | Sort-Object FullName) {
            $stream = $entry.Open()
            try {
                $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
            } finally {
                $stream.Dispose()
            }

            [pscustomobject]@{
                Name = $entry.FullName
                Length = $entry.Length
                CompressedLength = $entry.CompressedLength
                LastWriteTime = $entry.LastWriteTime.UtcDateTime.ToString("o")
                Sha256 = $hash
            }
        }
    } finally {
        $zip.Dispose()
        $sha.Dispose()
    }
}

$referenceEntries = Get-ZipEntryFingerprint -Path $ReferenceApk
$candidateEntries = Get-ZipEntryFingerprint -Path $CandidateApk
$entryNames = @($referenceEntries.Name + $candidateEntries.Name | Sort-Object -Unique)

# Index by entry name once (O(n)) instead of re-scanning both full arrays per
# name with Where-Object (O(n^2) — millions of comparisons for a real APK).
$referenceByName = @{}
foreach ($entry in $referenceEntries) { $referenceByName[$entry.Name] = $entry }
$candidateByName = @{}
foreach ($entry in $candidateEntries) { $candidateByName[$entry.Name] = $entry }

$diffs = foreach ($name in $entryNames) {
    $reference = $referenceByName[$name]
    $candidate = $candidateByName[$name]

    if (
        -not $reference -or
        -not $candidate -or
        $reference.Length -ne $candidate.Length -or
        $reference.CompressedLength -ne $candidate.CompressedLength -or
        $reference.LastWriteTime -ne $candidate.LastWriteTime -or
        $reference.Sha256 -ne $candidate.Sha256
    ) {
        [pscustomobject]@{
            Name = $name
            ReferenceSha256 = $reference.Sha256
            CandidateSha256 = $candidate.Sha256
            ReferenceLength = $reference.Length
            CandidateLength = $candidate.Length
            ReferenceCompressedLength = $reference.CompressedLength
            CandidateCompressedLength = $candidate.CompressedLength
            ReferenceLastWriteTime = $reference.LastWriteTime
            CandidateLastWriteTime = $candidate.LastWriteTime
        }
    }
}

if ($diffs) {
    $diffs | Format-Table -AutoSize | Out-String | Write-Error
    throw "APK ZIP entry content differs."
}

$referenceFileHash = (Get-FileHash -LiteralPath (Resolve-Path -LiteralPath $ReferenceApk) -Algorithm SHA256).Hash.ToLowerInvariant()
$candidateFileHash = (Get-FileHash -LiteralPath (Resolve-Path -LiteralPath $CandidateApk) -Algorithm SHA256).Hash.ToLowerInvariant()

if ($referenceFileHash -eq $candidateFileHash) {
    Write-Output "APK files match byte-for-byte: $referenceFileHash"
} else {
    Write-Output "APK ZIP entries match. Full APK hashes differ outside ZIP entries, usually in the APK Signing Block."
    Write-Output "Reference SHA256: $referenceFileHash"
    Write-Output "Candidate SHA256: $candidateFileHash"
}
