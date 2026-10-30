package com.sysadmindoc.callshield.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import com.sysadmindoc.callshield.ui.theme.CatGreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At large font sizes the bottom bar shows only the selected tab's label, and
 * Material leaves an unselected tab's hidden label unplaced. Compose's
 * accessibility layer skips unplaced nodes, so the icon is then the only thing
 * a screen reader can name the tab by.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en-rUS-w360dp-h640dp")
class NavItemAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `every tab keeps its name for screen readers at the largest font`() {
        assertEquals(TABS.map { listOf(it.second) }, spokenTabNames(fontScale = 2.0f))
    }

    @Test
    fun `each tab's name is read once at the default font`() {
        assertEquals(TABS.map { listOf(it.second) }, spokenTabNames(fontScale = 1.0f))
    }

    private fun spokenTabNames(fontScale: Float): List<List<String>> {
        composeRule.setContent {
            CallShieldTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    NavigationBar {
                        TABS.forEachIndexed { index, (icon, label) ->
                            NavItem(index == 0, {}, icon, label, CatGreen)
                        }
                    }
                }
            }
        }
        return composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.left }
            .map(::spokenWords)
    }

    /**
     * A tab's descriptions and text, its own and its placed descendants', which a screen reader
     * joins into one name. Like Compose's accessibility delegate, it doesn't look below a
     * clearAndSetSemantics node, which is how Material hides the icon while the label shows.
     */
    private fun spokenWords(node: SemanticsNode): List<String> {
        if (!node.layoutInfo.isPlaced) return emptyList()
        val config = node.config
        val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val children = if (config.isClearingSemantics) emptyList() else node.children.flatMap(::spokenWords)
        return descriptions + texts + children
    }

    private companion object {
        val TABS =
            listOf(
                Icons.Default.Home to "Home",
                Icons.Default.History to "Activity",
                Icons.Default.Search to "Lookup",
                Icons.Default.Tune to "Blocklist",
                Icons.Default.MoreHoriz to "More",
            )
    }
}
