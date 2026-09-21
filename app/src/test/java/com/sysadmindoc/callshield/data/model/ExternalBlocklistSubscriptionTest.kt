package com.sysadmindoc.callshield.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalBlocklistSubscriptionTest {
    @Test
    fun `a row names only the host a list comes from`() {
        assertEquals("lists.example.test", subscription("https://lists.example.test/path/daily.txt?key=1").host)
        assertEquals("raw.githubusercontent.com", subscription("https://raw.githubusercontent.com/o/r/main/list.txt").host)
    }

    @Test
    fun `an address with no readable host is shown whole`() {
        assertEquals("not a url", subscription("not a url").host)
    }

    private fun subscription(url: String) = ExternalBlocklistSubscription(id = "id", label = "List", url = url)
}
