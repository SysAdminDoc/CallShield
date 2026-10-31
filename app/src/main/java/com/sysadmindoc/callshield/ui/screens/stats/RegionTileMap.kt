package com.sysadmindoc.callshield.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.areacodes.AreaCodeLookup
import com.sysadmindoc.callshield.ui.theme.Black
import com.sysadmindoc.callshield.ui.theme.CatOverlay
import com.sysadmindoc.callshield.ui.theme.CatPeach
import com.sysadmindoc.callshield.ui.theme.CatSubtext
import com.sysadmindoc.callshield.ui.theme.CatText
import com.sysadmindoc.callshield.ui.theme.Surface
import kotlin.math.ceil
import kotlin.math.sqrt

private const val EMPTY_CELL = ".."
private const val SHADE_STEPS = 4
private const val TOP_REGION_COUNT = 3

/** Every state in its usual spot on a tile grid map, so no map data ships and nothing leaves the phone. */
private val US_ROWS =
    parseTileRows(
        "AK .. .. .. .. .. .. .. .. .. ME",
        ".. .. .. .. .. .. .. .. .. VT NH",
        "WA ID MT ND MN IL WI MI NY RI MA",
        "OR NV WY SD IA IN OH PA NJ CT ..",
        "CA UT CO NE MO KY WV VA MD DE ..",
        ".. AZ NM KS AR TN NC SC DC .. ..",
        ".. .. .. OK LA MS AL GA .. .. ..",
        "HI .. .. TX .. .. .. .. FL .. PR",
    )

/** AreaCodeLookup files the shared codes under NT (867) and NS (902), so those tiles stand for their neighbors too. */
private val CANADA_ROWS = parseTileRows("NT BC AB SK MB ON QC NB NS NL ..")

private val CANADA_REGIONS = CANADA_ROWS.flatten().filterNotNull().toSet()
private val MAPPED_REGIONS = US_ROWS.flatten().filterNotNull().toSet() + CANADA_REGIONS

private fun parseTileRows(vararg rows: String): List<List<String?>> =
    rows.map { row -> row.split(" ").map { cell -> cell.takeUnless { it == EMPTY_CELL } } }

/**
 * The log's area code counts added up by state or province. A code with no
 * tile (the Caribbean, toll-free) stays out, and the area code list still
 * shows it.
 */
internal fun countsByRegion(areaCodeCounts: List<Pair<String, Int>>): Map<String, Int> =
    areaCodeCounts
        .mapNotNull { (areaCode, count) ->
            AreaCodeLookup.getRegionCode("+1${areaCode}0000000")?.takeIf { it in MAPPED_REGIONS }?.let { it to count }
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .mapValues { (_, counts) -> counts.sum() }

/** The map's rows, with Canada's on top only when the log holds a Canadian number. */
internal fun regionTileRows(counts: Map<String, Int>): List<List<String?>> =
    if (counts.keys.any { it in CANADA_REGIONS }) CANADA_ROWS + US_ROWS else US_ROWS

/** How dark a tile is, 0 for no calls. The square root keeps one busy state from washing out the rest. */
internal fun shadeStep(
    count: Int,
    maxCount: Int,
): Int =
    if (count <= 0 || maxCount <= 0) {
        0
    } else {
        ceil(sqrt(count.toDouble() / maxCount) * SHADE_STEPS).toInt().coerceIn(1, SHADE_STEPS)
    }

/** The busiest places, "TX 12, CA 5, FL 3", most first and then by name. */
internal fun topRegions(counts: Map<String, Int>): String =
    counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(TOP_REGION_COUNT)
        .joinToString(", ") { (region, count) -> "$region $count" }

/** The label color that reads better on [background], [light] or [dark]. */
internal fun inkOn(
    background: Color,
    light: Color,
    dark: Color,
): Color = if (contrast(background, light) >= contrast(background, dark)) light else dark

private fun contrast(
    first: Color,
    second: Color,
): Float {
    val high = maxOf(first.luminance(), second.luminance())
    val low = minOf(first.luminance(), second.luminance())
    return (high + 0.05f) / (low + 0.05f)
}

@Composable
private fun shadeColor(step: Int): Color =
    when (step) {
        0 -> CatOverlay.copy(alpha = 0.1f)
        1 -> CatPeach.copy(alpha = 0.3f)
        2 -> CatPeach.copy(alpha = 0.5f)
        3 -> CatPeach.copy(alpha = 0.75f)
        else -> CatPeach
    }

/** The state and province map. TalkBack reads it as one line, the busiest places, and the area code list has the rest. */
@Composable
internal fun RegionTileMap(
    counts: Map<String, Int>,
    modifier: Modifier = Modifier,
) {
    val maxCount = counts.values.maxOrNull() ?: 0
    val summary = stringResource(R.string.stats_region_map_summary, topRegions(counts))
    Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
        // A map keeps its west on the left in every language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                regionTileRows(counts).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { region ->
                            if (region == null) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                RegionTile(region, shadeStep(counts[region] ?: 0, maxCount))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(summary, style = MaterialTheme.typography.labelSmall, color = CatSubtext, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            ShadeKey()
        }
    }
}

@Composable
private fun RowScope.RegionTile(
    region: String,
    step: Int,
) {
    val fill = shadeColor(step)
    Box(
        modifier =
            Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = region,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (step > 0) FontWeight.Bold else FontWeight.Normal,
            color = if (step > 0) inkOn(fill.compositeOver(Surface), CatText, Black) else CatSubtext,
        )
    }
}

@Composable
private fun ShadeKey() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.stats_region_map_fewer), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
        Spacer(Modifier.size(2.dp))
        for (step in 1..SHADE_STEPS) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(shadeColor(step)))
        }
        Spacer(Modifier.size(2.dp))
        Text(stringResource(R.string.stats_region_map_more), style = MaterialTheme.typography.labelSmall, color = CatSubtext)
    }
}
