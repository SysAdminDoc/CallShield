package com.sysadmindoc.callshield.data.remote

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Shared OkHttpClient for all network requests in the app.
 *
 * OkHttpClient instances are expensive — each creates its own connection pool,
 * thread pool, and cache. Sharing a single instance enables HTTP/2 connection
 * reuse across GitHub API, ExternalLookup, URL threat feeds, and community
 * report calls.
 *
 * Callers that need different timeouts should use [OkHttpClient.newBuilder] to
 * create a derived client that shares the same connection pool:
 *   `HttpClient.shared.newBuilder().readTimeout(5, SECONDS).build()`
 *
 * Certificate pinning covers every first-party and enrichment endpoint that
 * CallShield contacts directly. Keep at least two pins per host, prefer CA
 * roots over leaves and intermediates (both rotate), and never pin a Let's
 * Encrypt intermediate. `scripts/check_live_pins.py` checks every pin set
 * against the live chain and gates each release.
 */
object HttpClient {
    internal val pinnedEndpointPins: Map<String, List<String>> =
        mapOf(
            "api.github.com" to
                listOf(
                    "sha256/QVnLDkTvhX8bfBbaP6XeqWLCOja893s79lYfjQc/hWI=",
                    "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=",
                    "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=",
                ),
            // Let's Encrypt roots, never an LE leaf or intermediate: LE rotates
            // intermediates between renewals, and the 2026 move to its
            // Generation Y hierarchy broke the old leaf + R12 pins on every
            // device. The Sectigo root GitHub already uses for api.github.com
            // covers a change of CA vendor.
            "raw.githubusercontent.com" to
                listOf(
                    // ISRG Root X1
                    "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
                    // ISRG Root YR
                    "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=",
                    // ISRG Root X2
                    "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
                    // ISRG Root YE
                    "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                    // Sectigo Public Server Authentication Root E46
                    "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=",
                ),
            "callshield-reports.snafumatthew.workers.dev" to
                listOf(
                    "sha256/mFN8iYYCY74a/Mj4kqR2h2ucvW44AL/rDv3wz+XMW/Y=",
                    "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
                    "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
                ),
            "spam.skipcalls.app" to
                listOf(
                    "sha256/eQ8pDLuDDRfLl7eY9WehMyMiIoWDCVCPvCWKe06E1AE=",
                    "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
                    "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
                ),
            // Let's Encrypt roots; the old leaf + E7 pins died in the same
            // Generation Y rollover as the raw host.
            "phoneblock.net" to
                listOf(
                    // ISRG Root X1
                    "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
                    // ISRG Root X2
                    "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
                    // ISRG Root YE
                    "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
                    // ISRG Root YR
                    "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=",
                ),
            "www.whocalledme.com" to
                listOf(
                    "sha256/Q97jgORCCdhYcbgtgJzZ2aWimuviu6H8LvWqkCBZTyM=",
                    "sha256/8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=",
                    "sha256/Ko8tivDrEjiY90yGasP6ZpBU4jwXvHqVvQI0GS3GNdA=",
                ),
            "api.opencnam.com" to
                listOf(
                    "sha256/KM+xdFD9/Mj+CYgTGCu45A1uwvPEHWw7kTpeX3zfEEs=",
                    "sha256/SDG5orEv8iX6MNenIAxa8nQFNpROB/6+llsZdXHZNqs=",
                    "sha256/i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=",
                ),
            "urlhaus-api.abuse.ch" to
                listOf(
                    "sha256/Yz0ts4M9B9b1XBQTQtITniseuxd86RWgvl5+aekJM8Q=",
                    "sha256/A7AXWj1rjKywVBFqQcQvoHEEWHeViDOFXrwzRs984Xc=",
                    "sha256/68l4rg3Z5YItaxllJZb2IMk9fK76lSGRywUKYyypAF8=",
                ),
        )

    internal val certificatePinner: CertificatePinner =
        CertificatePinner
            .Builder()
            .apply {
                pinnedEndpointPins.forEach { (host, pins) ->
                    pins.forEach { pin -> add(host, pin) }
                }
            }.build()

    val shared: OkHttpClient =
        OkHttpClient
            .Builder()
            .certificatePinner(certificatePinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * True when [error], or anything in its cause chain, is a TLS peer
     * verification failure: a certificate pin or hostname mismatch. Retrying
     * cannot fix that, only an app update with working pins can, so callers
     * surface it separately from ordinary network trouble.
     */
    fun isCertificateTrustFailure(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is SSLPeerUnverifiedException }

    private const val MAX_CAUSE_DEPTH = 8
}
