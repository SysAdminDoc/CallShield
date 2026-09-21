package com.sysadmindoc.callshield.data.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedMirrorTest {
    @After
    fun clear() = FeedMirror.set(null)

    @Test
    fun `a usable base gets a trailing slash so repository paths append cleanly`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master/",
            FeedMirror.normalize(" https://cdn.jsdelivr.net/gh/SysAdminDoc/CallShield@master "),
        )
        assertEquals("https://mirror.example.test/callshield/", FeedMirror.normalize("https://mirror.example.test/callshield/#top"))
    }

    @Test
    fun `anything that isn't a plain https base is refused`() {
        assertNull(FeedMirror.normalize("http://mirror.example.test/"))
        assertNull(FeedMirror.normalize("https://user:secret@mirror.example.test/"))
        assertNull(FeedMirror.normalize("https://mirror.example.test/?token=1"))
        assertNull(FeedMirror.normalize("file:///sdcard/feeds/"))
        assertNull(FeedMirror.normalize(""))
    }

    @Test
    fun `urls are the base plus the repository path, and nothing without a mirror`() {
        assertNull(FeedMirror.urlFor("data/hot_numbers.json"))

        FeedMirror.set("https://mirror.example.test/callshield")

        assertEquals("https://mirror.example.test/callshield/data/hot_numbers.json", FeedMirror.urlFor("data/hot_numbers.json"))
    }

    @Test
    fun `a stored value that stopped being usable clears the mirror`() {
        FeedMirror.set("https://mirror.example.test/")
        FeedMirror.set("http://mirror.example.test/")

        assertNull(FeedMirror.baseUrl)
    }
}
