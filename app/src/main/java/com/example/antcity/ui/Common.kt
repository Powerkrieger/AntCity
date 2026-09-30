package com.example.antcity.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.antcity.core.Res

@Composable
fun AntCityTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFE0B25B), secondary = Color(0xFF9CC17A))
    } else {
        lightColorScheme(primary = Color(0xFF8A5A14), secondary = Color(0xFF4F7A2B))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatDuration(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60}m ${s % 60}s"
        else -> "${s}s"
    }
}

val resourceIcons = listOf("🍖", "🪵", "🪨", "🪙")

fun Res.entries() = listOf(food, wood, stone, gold).zip(resourceIcons)

/** A cost line such as "🪵 40  🪨 10"; amounts the player can't afford are shown in red. */
@Composable
fun CostRow(cost: Res, have: Res?, modifier: Modifier = Modifier) {
    val available = have?.entries()?.map { it.first }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cost.entries().forEachIndexed { i, (amount, icon) ->
            if (amount > 0) {
                val short = available != null && available[i] < amount
                Text(
                    "$icon $amount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
