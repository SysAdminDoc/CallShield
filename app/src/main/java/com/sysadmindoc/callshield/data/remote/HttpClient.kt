package com.sysadmindoc.callshield.data.remote

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttpClient for all network requests in the app.
 *
 * OkHttpClient instances are expensive — each creates its own connection pool,
 * thread pool, and cache. Sharing a single instance enables HTTP/2 connection
 * reuse across GitHub API, ExternalLookup, URLhaus, and community report calls.
 *
 * Callers that need different timeouts should use [OkHttpClient.newBuilder] to
 * create a derived client that shares the same connection pool:
 *   `HttpClient.shared.newBuilder().readTimeout(5, SECONDS).build()`
 *
 * Certificate pinning covers every first-party and enrichment endpoint that
 * CallShield contacts directly. Keep at least one leaf/intermediate backup
 * pin per host and verify these during every dependency/security release.
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
            "raw.githubusercontent.com" to
                listOf(
                    "sha256/W+jBdq3o4qj8cXXBURwKqofJk8BG59NEPXOEgMh53sA=",
                    "sha256/kZwN96eHtZftBWrOZUsd6cA4es80n3NzSk/XtYz2EqQ=",
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
            "phoneblock.net" to
                listOf(
                    "sha256/QSCRpv+KcUv9sLsdsMT4utQr9dOiwcGQXplf7Nc7Igw=",
                    "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU=",
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
            "phonevalidation.abstractapi.com" to
                listOf(
                    "sha256/+RrudNqgW6672HhSINNZzjvkzMGcd3TpA3LqIDVqAWk=",
                    "sha256/DxH4tt40L+eduF6szpY6TONlxhZhBd+pJ9wbHlQ2fuw=",
                    "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=",
                ),
        )

    internal val certificatePinner: CertificatePinner =
        CertificatePinner
            .Builder()
            .apply {
                pinnedEndpointPins.forEach { (host, pins) ->
                    add(host, *pins.toTypedArray())
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
}
