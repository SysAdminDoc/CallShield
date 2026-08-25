$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "release-signing-policy.ps1")

$expected = "920e583ae6ce9f3863a6b3b8847e927d53a66c38a245e12e30ce124c9f4a75f5"
$dn = "CN=CallShield, O=SysAdminDoc"

function Assert-Throws {
    param(
        [scriptblock]$Action,
        [string]$Pattern
    )
    try {
        & $Action
        throw "Expected action to fail with pattern: $Pattern"
    } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw }
    }
}

$validOutput = @(
    "Signer #1 certificate DN: $dn",
    "Signer #1 certificate SHA-256 digest: $expected",
    "V3.0 Signer: certificate DN: $dn",
    "V3.0 Signer: certificate SHA-256 digest: $expected"
)
$result = Assert-ReleaseSignerPolicy -CertificateOutput $validOutput -ExpectedSignerSha256 $expected
if ($result.Sha256 -ne $expected -or $result.DistinguishedName -ne $dn) {
    throw "Pinned signer was not returned from valid repeated scheme output."
}

$lookalikeDigest = "a" * 64
Assert-Throws -Pattern "does not match" -Action {
    Assert-ReleaseSignerPolicy -CertificateOutput @(
        "Signer #1 certificate DN: $dn",
        "Signer #1 certificate SHA-256 digest: $lookalikeDigest"
    ) -ExpectedSignerSha256 $expected
}

Assert-Throws -Pattern "exactly one" -Action {
    Assert-ReleaseSignerPolicy -CertificateOutput ($validOutput + @(
        "Signer #2 certificate DN: CN=Second, O=SysAdminDoc",
        "Signer #2 certificate SHA-256 digest: $lookalikeDigest"
    )) -ExpectedSignerSha256 $expected
}

Assert-Throws -Pattern "DEBUG-signed" -Action {
    Assert-ReleaseSignerPolicy -CertificateOutput @(
        "Signer #1 certificate DN: CN=Android Debug, O=Android, C=US",
        "Signer #1 certificate SHA-256 digest: $expected"
    ) -ExpectedSignerSha256 $expected
}

$colonDigest = ($expected -split '(.{2})' | Where-Object { $_ }) -join ':'
$normalized = ConvertTo-SignerSha256 -Value $colonDigest.ToUpperInvariant()
if ($normalized -ne $expected) { throw "Colon-delimited signer digest did not normalize." }

Write-Output "release signing policy tests passed"
