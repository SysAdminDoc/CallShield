package com.sysadmindoc.callshield.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a 360x780dp phone with 3-button navigation at font scale 1.3, the
 * filtered-empty Blocked log had about 187dp for a 217dp empty card, so its
 * "Show all activity" action was cut off, and Blocklist's empty card sat under
 * the Add button. The heights are the content area between the top bar and
 * the bottom navigation.
 */
class ShortContentTest {
    @Test
    fun `a 360x780dp phone is short at any text size`() {
        assertTrue(isShortContent(564.dp, 1.3f))
        assertTrue(isShortContent(564.dp, 1.0f))
        assertTrue(isShortContent(596.dp, 1.0f))
    }

    @Test
    fun `a 412x915dp phone is short only with large text`() {
        assertFalse(isShortContent(731.dp, 1.0f))
        assertFalse(isShortContent(731.dp, 1.15f))
        assertTrue(isShortContent(731.dp, 1.3f))
    }

    @Test
    fun `a font scale below one never makes a screen roomier than its height`() {
        assertTrue(isShortContent(564.dp, 0.85f))
    }
}
