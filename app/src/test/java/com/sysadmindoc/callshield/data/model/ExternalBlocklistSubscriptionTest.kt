package com.sysadmindoc.callshield.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A settings row has room for the host a list comes from, and TalkBack reads
 * what the row shows. The address can carry a token in its query, so the row
 * never falls back to it, including for addresses java.net.URI can't read but
 * the list client fetches anyway.
 */
class ExternalBlocklistSubscriptionTest {
    @Test
    fun `a row names only the host a list comes from`() {
        assertEquals("lists.example.test", subscription("https://lists.example.test/path/daily.txt?key=1").host)
        assertEquals("raw.githubusercontent.com", subscription("https://raw.githubusercontent.com/o/r/main/list.txt").host)
    }

    @Test
    fun `addresses java's URI can't read still show their host alone`() {
        listOf(
            "https://my_lists.example.test/list.csv?token=s3cret" to "my_lists.example.test",
            "https://lists.example.test/list.csv?token=s3cret|v2" to "lists.example.test",
            "https://lists.example.test/list.csv?q={x}&token=s3cret" to "lists.example.test",
        ).forEach { (url, host) -> assertEquals(url, host, subscription(url).host) }
    }

    @Test
    fun `an address with no readable host shows no host rather than itself`() {
        assertEquals("", subscription("not a url").host)
        assertEquals("", subscription("lists.example.test/list.csv?token=s3cret").host)
    }

    private fun subscription(url: String) = ExternalBlocklistSubscription(id = "id", label = "List", url = url)
}
