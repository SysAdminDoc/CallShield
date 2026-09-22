package com.sysadmindoc.callshield.data.remote

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Detached signatures on the feeds the app downloads from this repository.
 *
 * Each signed feed has `<file>.sig` beside it: a base64 DER ECDSA P-256
 * signature over the file's exact bytes, made with the maintainer's key by
 * scripts/feed_signing.py. Until these, TLS pinning alone decided whether a
 * download was genuine, and pinning broke for seven weeks from 2026-08-02 when
 * GitHub's certificate changed. A signature holds whichever host or CA serves
 * the file.
 */
internal object FeedSignature {
    /**
     * The keys a feed may be signed with, as base64 X.509 SubjectPublicKeyInfo.
     * The first signs; the second is a backup kept offline, so losing one key
     * doesn't strand every install. scripts/feed_signing.py reads this list, so
     * keep each key a quoted string. Rotation is described in data/README.md.
     */
    internal val TRUSTED_KEYS =
        listOf(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESGK0kjIAEM7FP2RBLbWctHhYVP7LcNVJmWiuh6k6hkBGHfVXaqw+TOaSVQtbZLZeN5OThnqd0WTEF/CkBJ2gdA==",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3eBrWqtgDaKc2HFC6EPtENrh8nlCH/bZ5PstgPpIJBVL8ZEf35UfwtbqWKJ/fQDi1pYKLmvMv/0OC3KSug/fxg==",
        )

    /** A signature file is one base64 line; anything longer isn't one. */
    internal const val MAX_SIGNATURE_BYTES = 512L

    private val trustedKeys: List<PublicKey> by lazy { TRUSTED_KEYS.map(::decodePublicKey) }

    fun decodePublicKey(encoded: String): PublicKey =
        KeyFactory
            .getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))

    /** True when [signatureText] is a signature over [body] by one of [keys]. */
    fun verifies(
        body: ByteArray,
        signatureText: String,
        keys: List<PublicKey> = trustedKeys,
    ): Boolean {
        val signature =
            try {
                Base64.getDecoder().decode(signatureText.trim())
            } catch (_: IllegalArgumentException) {
                return false
            }
        if (signature.isEmpty()) return false
        return keys.any { key ->
            try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(body)
                    verify(signature)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
        }
    }
}
