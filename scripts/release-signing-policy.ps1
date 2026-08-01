Set-StrictMode -Version Latest

function ConvertTo-SignerSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $normalized = ($Value -replace '[^0-9A-Fa-f]', '').ToLowerInvariant()
    if ($normalized.Length -ne 64) {
        throw "Signer SHA-256 must contain exactly 64 hexadecimal characters."
    }
    return $normalized
}

function Assert-ReleaseSignerPolicy {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CertificateOutput,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedSignerSha256
    )

    $expected = ConvertTo-SignerSha256 -Value $ExpectedSignerSha256
    $dns = @($CertificateOutput |
        Where-Object { $_ -match 'certificate DN:' } |
        ForEach-Object { ($_ -replace '^.*?certificate DN:\s*', '').Trim() } |
        Select-Object -Unique)
    $digests = @($CertificateOutput |
        Where-Object { $_ -match 'certificate SHA-256 digest:' } |
        ForEach-Object {
            $value = ($_ -replace '^.*?certificate SHA-256 digest:\s*', '').Trim()
            ConvertTo-SignerSha256 -Value $value
        } |
        Select-Object -Unique)

    if ($dns.Count -eq 0 -or $digests.Count -eq 0) {
        throw "No signer certificate found. A release APK must be signed with the pinned release key."
    }
    if ($dns.Count -gt 1 -or $digests.Count -gt 1) {
        throw "Release APK must have exactly one distinct signer certificate."
    }

    $dn = $dns[0]
    $actual = $digests[0]
    if ($dn -match 'CN=Android Debug' -or $dn -match 'O=Android Debug') {
        throw "Release APK is DEBUG-signed (DN: $dn)."
    }
    if ($actual -ne $expected) {
        throw "Release APK signer SHA-256 does not match the pinned release certificate (actual: $actual)."
    }

    return [PSCustomObject]@{
        DistinguishedName = $dn
        Sha256 = $actual
    }
}
