package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.genkan.library.GameEntity
import app.genkan.library.LibraryPane
import app.genkan.library.SyncStatus
import coil3.compose.AsyncImage

private val CoverMinWidth = 120.dp
private val GridSpacing = 16.dp

@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val platforms by viewModel.platforms.collectAsStateWithLifecycle()
    val games by viewModel.games.collectAsStateWithLifecycle()
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PlatformRail(
            names = platforms.map { "${it.name}  ${it.romCount}" },
            selected = focus.platformIndex,
            railFocused = focus.pane == LibraryPane.PLATFORMS,
            onSelect = viewModel::selectPlatform,
        )
        Column(Modifier.fillMaxSize()) {
            Header(
                title = platforms.getOrNull(focus.platformIndex)?.name ?: "Library",
                sync = sync,
                focusedGame = games.getOrNull(focus.gameIndex)?.takeIf { focus.pane == LibraryPane.GAMES },
            )
            if (platforms.isEmpty()) {
                EmptyLibrary(sync)
            } else {
                GameGrid(
                    games = games,
                    focusedIndex = focus.gameIndex.takeIf { focus.pane == LibraryPane.GAMES },
                    coverUrl = viewModel::coverUrl,
                    onColumns = { viewModel.columns = it },
                    onSelect = viewModel::selectGame,
                )
            }
        }
    }
}

@Composable
private fun PlatformRail(names: List<String>, selected: Int, railFocused: Boolean, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { if (names.isNotEmpty()) listState.animateScrollToItem(selected) }
    LazyColumn(
        state = listState,
        modifier = Modifier.width(260.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(names) { index, name ->
            val colors = MaterialTheme.colorScheme
            val isSelected = index == selected
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (isSelected) colors.primary.copy(alpha = if (railFocused) 0.25f else 0.12f) else colors.surface, shape)
                    .border(2.dp, if (isSelected && railFocused) colors.primary else Color.Transparent, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Header(title: String, sync: SyncStatus, focusedGame: GameEntity?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.primary)
            Text(
                focusedGame?.let { "${it.name}  ·  ${formatSize(it.fsSizeBytes)}" } ?: " ",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(syncLabel(sync), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun GameGrid(
    games: List<GameEntity>,
    focusedIndex: Int?,
    coverUrl: (GameEntity) -> String?,
    onColumns: (Int) -> Unit,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = ((maxWidth + GridSpacing) / (CoverMinWidth + GridSpacing)).toInt().coerceAtLeast(1)
        LaunchedEffect(columns) { onColumns(columns) }
        val gridState = rememberLazyGridState()
        LaunchedEffect(focusedIndex, games.size) {
            val index = focusedIndex ?: return@LaunchedEffect
            val visible = gridState.layoutInfo.visibleItemsInfo
            val fullyVisible = visible.filter { it.offset.y >= 0 && it.offset.y + it.size.height <= gridState.layoutInfo.viewportEndOffset }
            if (fullyVisible.none { it.index == index }) gridState.animateScrollToItem(index)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                GameCard(game, coverUrl(game), focused = index == focusedIndex, onClick = { onSelect(index) })
            }
        }
    }
}

@Composable
private fun GameCard(game: GameEntity, cover: String?, focused: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape)
                .background(colors.surface)
                .border(if (focused) 4.dp else 0.dp, if (focused) colors.primary else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                game.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = game.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            game.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) colors.primary else colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun EmptyLibrary(sync: SyncStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            when (sync) {
                is SyncStatus.Running, SyncStatus.Idle -> "Loading your RomM library…"
                is SyncStatus.Failed -> "Couldn't load the library: ${sync.message}\nPress Y to retry."
                is SyncStatus.Done -> "Your RomM library is empty."
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}

private fun syncLabel(sync: SyncStatus): String = when (sync) {
    SyncStatus.Idle -> ""
    is SyncStatus.Running -> "Syncing ${sync.platform}…"
    is SyncStatus.Done -> "Y: sync  ·  Start: settings"
    is SyncStatus.Failed -> "Sync failed · Y to retry"
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "—"
}
