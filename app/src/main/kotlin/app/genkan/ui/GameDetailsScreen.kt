package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/** The bottom screen while browsing: the focused game's cover, metadata and history. */
@Composable
fun GameDetailsScreen(viewModel: GameDetailsViewModel, onShowReader: () -> Unit) {
    val details by viewModel.details.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val current = details ?: return Box(Modifier.fillMaxSize().background(colors.background))
    val game = current.focus.game
    val isApp = game.platformSlug == LocalShelves.APPS_SLUG

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current.focus.platformName,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Chip("Backlog", onShowReader)
        }
        Row(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            current.focus.cover?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = game.name,
                    contentScale = if (isApp) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.width(170.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(game.name, style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                listOfNotNull(game.releaseYear?.toString(), game.developer).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                game.genres?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                historyLines(current).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                game.summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Download state, play time and reading, one fact per line; only what is known. */
private fun historyLines(details: GameDetails): List<String> = buildList {
    val game = details.focus.game
    when (details.download?.state) {
        DownloadState.INSTALLED -> add("Downloaded" + sizeSuffix(game.fsSizeBytes))
        DownloadState.QUEUED, DownloadState.RUNNING -> add("Downloading…")
        DownloadState.FAILED -> add("Download failed")
        null -> if (!LocalShelves.isLocal(game)) add("Not downloaded" + sizeSuffix(game.fsSizeBytes))
    }
    details.play?.takeIf { it.plays > 0 }?.let { play ->
        val last = play.lastPlayed?.let { "  ·  last ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" }.orEmpty()
        add("Played ${duration(play.totalMs)}$last")
    }
    details.reading.takeIf { it.characters > 0 }?.let {
        add("Read ${"%,d".format(it.characters)} characters in ${duration(it.activeMs)}")
    }
}

private fun sizeSuffix(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "  ·  %.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "  ·  ${bytes / (1L shl 20)} MB"
}

private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
