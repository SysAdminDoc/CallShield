package com.sysadmindoc.callshield.data.remote

import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
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
        )

    internal val certificatePinner: CertificatePinner =
        CertificatePinner
            .Builder()
            .apply {
                pinnedEndpointPins.forEach { (host, pins) ->
                    pins.forEach { pin -> add(host, pin) }
                }
            }.build()

    /**
     * Set by the unit-test task in app/build.gradle.kts. Under it, [shared] and
     * every client derived from it resolve only loopback hosts, so a unit test
     * can't reach a live server. One did: a receiver test posted a real report
     * to the community Worker on every run, and the Worker commits each report
     * it accepts to the public repo. An interceptor a test installs still
     * answers first, because DNS only runs for a request that is leaving.
     */
    internal const val UNIT_TEST_PROPERTY = "callshield.unitTest"

    /** Sockets that connect only to a loopback address, for unit tests. */
    private object LoopbackOnlySocketFactory : SocketFactory() {
        override fun createSocket(): Socket = LoopbackOnlySocket()

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = LoopbackOnlySocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket =
            LoopbackOnlySocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = LoopbackOnlySocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket =
            LoopbackOnlySocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    private class LoopbackOnlySocket : Socket() {
        override fun connect(
            endpoint: SocketAddress,
            timeout: Int,
        ) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address == null || !address.isLoopbackAddress) throw ConnectException("Unit tests must not reach $endpoint")
            super.connect(endpoint, timeout)
        }
    }

    private val loopbackOnlyDns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname == "localhost" || hostname == "127.0.0.1" || hostname == "::1") return Dns.SYSTEM.lookup(hostname)
                throw UnknownHostException("Unit tests must not reach $hostname")
            }
        }

    val shared: OkHttpClient =
        OkHttpClient
            .Builder()
            .certificatePinner(certificatePinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .apply {
                if (System.getProperty(UNIT_TEST_PROPERTY) == "true") {
                    dns(loopbackOnlyDns)
                    // An address given as an IP skips DNS, and a proxy would connect
                    // for the test, so the socket itself refuses anything but loopback.
                    socketFactory(LoopbackOnlySocketFactory)
                    proxy(Proxy.NO_PROXY)
                }
            }.build()

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
