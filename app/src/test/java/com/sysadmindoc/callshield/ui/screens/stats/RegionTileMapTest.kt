package com.sysadmindoc.callshield.ui.screens.stats

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The statistics map: where a count lands, how dark it shades and what TalkBack hears. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegionTileMapTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `area codes add up under their state`() {
        val counts = countsByRegion(listOf("214" to 4, "713" to 3, "415" to 2))

        assertEquals(mapOf("TX" to 7, "CA" to 2), counts)
    }

    @Test
    fun `a code with no tile stays off the map`() {
        // Jamaica has no region, and toll-free belongs to no place.
        assertTrue(countsByRegion(listOf("876" to 5, "800" to 5)).isEmpty())
    }

    @Test
    fun `no state is missing or placed twice`() {
        val tiles = regionTileRows(mapOf("ON" to 1)).flatten().filterNotNull()

        assertEquals(tiles.size, tiles.toSet().size)
        assertEquals(51 + 1 + 10, tiles.size)
    }

    @Test
    fun `every tile is a place the area code table can name`() {
        val named = (200..999).mapNotNullTo(HashSet()) { AreaCodeLookup.getRegionCode("+1${it}0000000") }

        val unnamed = regionTileRows(mapOf("ON" to 1)).flatten().filterNotNull().filterNot { it in named }

        assertEquals(emptyList<String>(), unnamed)
    }

    @Test
    fun `every row is the same width so the map stays aligned`() {
        assertEquals(setOf(11), regionTileRows(mapOf("ON" to 1)).map { it.size }.toSet())
    }

    @Test
    fun `Canada's row only appears with a Canadian number`() {
        assertFalse(regionTileRows(mapOf("TX" to 1)).flatten().contains("ON"))
        assertTrue(regionTileRows(mapOf("ON" to 1)).flatten().contains("TX"))
    }

    @Test
    fun `a busy state doesn't wash out a quiet one`() {
        assertEquals(0, shadeStep(0, 100))
        assertEquals(1, shadeStep(1, 100))
        assertEquals(4, shadeStep(100, 100))
        assertEquals(2, shadeStep(25, 100))
    }

    @Test
    fun `an empty log shades nothing`() {
        assertEquals(0, shadeStep(0, 0))
    }

    @Test
    fun `the busiest places come first and ties read by name`() {
        val line = topRegions(mapOf("FL" to 3, "CA" to 5, "TX" to 12, "AZ" to 3, "OH" to 1))

        assertEquals("TX 12, CA 5, AZ 3", line)
    }

    @Test
    fun `a tile's label takes the color that reads on it`() {
        assertEquals(Color.Black, inkOn(Color.White, Color.White, Color.Black))
        assertEquals(Color.White, inkOn(Color.Black, Color.White, Color.Black))
    }

    @Test
    fun `TalkBack hears the busiest places as one line`() {
        composeRule.setContent {
            CallShieldTheme {
                RegionTileMap(mapOf("TX" to 12, "CA" to 5))
            }
        }

        composeRule.onNodeWithContentDescription("TX 12, CA 5", substring = true).assertExists()
    }
}
