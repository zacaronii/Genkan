package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.core.SettingRow
import app.genkan.core.SettingSource

/**
 * A game's settings in the built-in player, on the bottom screen over its details: each row its
 * value and where that comes from (its default, learning, the catalog, its console, or this game),
 * so it's clear why the game runs as it does. A press moves a row to its next value for this game;
 * Reset puts them all back as its console has them. Close or Back closes it.
 */
@Composable
fun GameSettingsSheet(
    title: String,
    rows: List<SettingRow>,
    onPress: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxSize()
            // A touch anywhere on the sheet is the sheet's: it never reaches the buttons underneath.
            .pointerInput(Unit) {}
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rows.any { it.source == SettingSource.GAME }) Chip("Reset", onReset)
            Chip("Close", onClose)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(rows, key = { it.key }) { row -> SettingRowView(row) { onPress(row.key) } }
        }
    }
}

/** A setting's row: its name, its value, and where the value comes from. */
@Composable
private fun SettingRowView(row: SettingRow, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val own = row.source == SettingSource.GAME
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (own) colors.primary.copy(alpha = 0.18f) else colors.surface, shape)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(row.label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(row.value, style = MaterialTheme.typography.titleMedium, color = if (own) colors.primary else colors.onSurface)
            Text(row.source.label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
