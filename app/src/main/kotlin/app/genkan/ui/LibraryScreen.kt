package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.ContinuePlaying
import app.genkan.library.GameEntity
import app.genkan.library.LibraryFocus
import app.genkan.library.LibraryOrder
import app.genkan.library.OrderPanel
import app.genkan.library.LibraryPane
import app.genkan.library.LibraryView
import app.genkan.library.PlatformEntity
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
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val insert by viewModel.insert.collectAsStateWithLifecycle()
    val favourites by viewModel.favouriteIds.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val orderPanel by viewModel.orderPanel.collectAsStateWithLifecycle()
    LibraryContent(
        favourites = favourites,
        order = order,
        orderPanel = orderPanel,
        onToggleOrderPanel = viewModel::toggleOrderPanel,
        onOrder = viewModel::setOrder,
        view = view,
        platforms = platforms,
        games = games,
        focus = focus,
        sync = sync,
        downloads = downloads,
        notice = notice,
        coverModel = viewModel::coverModel,
        artModel = viewModel::artModel,
        emulatorFor = viewModel::emulatorFor,
        onSelectPlatform = viewModel::selectPlatform,
        onColumns = { viewModel.columns = it },
        onSelectGame = viewModel::selectGame,
        onToggleView = viewModel::toggleView,
        insert = insert,
        onSkipInsert = { viewModel.skipInsert() },
    )
}

/**
 * The library drawn from plain state, so it can be shown without a ViewModel (the screenshot
 * tests do), as the shelf or the grid by [view]. [onColumns] reports how many covers fit in a
 * grid row, for gamepad navigation. [artModel] turns a game's other artwork (large cover, spine,
 * logo, disc) into something Coil loads. [staticMedia] is for screenshots; see [GameShelf].
 * [insert] draws the insert animation over it while a game starts; [staticInsertProgress] fixes
 * the animation's progress, for screenshots.
 */
@Composable
fun LibraryContent(
    view: LibraryView,
    platforms: List<PlatformEntity>,
    games: List<GameEntity>,
    focus: LibraryFocus,
    sync: SyncStatus,
    downloads: Map<Long, DownloadEntity>,
    notice: String?,
    coverModel: (GameEntity) -> Any?,
    artModel: (String?) -> Any?,
    emulatorFor: (String) -> String?,
    onSelectPlatform: (Int) -> Unit,
    onColumns: (Int) -> Unit,
    onSelectGame: (Int) -> Unit,
    onToggleView: () -> Unit,
    staticMedia: Boolean? = null,
    insert: InsertState? = null,
    onSkipInsert: () -> Unit = {},
    staticInsertProgress: Float? = null,
    /** The games marked as favourites, for what R2 says. */
    favourites: Set<Long> = emptySet(),
    /** The consoles' sort and filter, and the panel's focused row while it is open. */
    order: LibraryOrder = LibraryOrder(),
    orderPanel: Int? = null,
    onToggleOrderPanel: () -> Unit = {},
    onOrder: (LibraryOrder) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        // While the insert animation has the disc or cartridge, the shelf's own goes back in its case.
        LibraryBody(
            view, platforms, games, focus, sync, downloads, notice, coverModel, artModel, emulatorFor,
            onSelectPlatform, onColumns, onSelectGame, onToggleView, if (insert != null) false else staticMedia, favourites,
            order.downloadedOnly,
        )
        if (orderPanel != null) OrderPanelCard(order, orderPanel, onOrder, onToggleOrderPanel, Modifier.align(Alignment.TopEnd))
        if (insert != null) InsertOverlay(insert, onSkipInsert, staticInsertProgress)
    }
}

@Composable
private fun LibraryBody(
    view: LibraryView,
    platforms: List<PlatformEntity>,
    games: List<GameEntity>,
    focus: LibraryFocus,
    sync: SyncStatus,
    downloads: Map<Long, DownloadEntity>,
    notice: String?,
    coverModel: (GameEntity) -> Any?,
    artModel: (String?) -> Any?,
    emulatorFor: (String) -> String?,
    onSelectPlatform: (Int) -> Unit,
    onColumns: (Int) -> Unit,
    onSelectGame: (Int) -> Unit,
    onToggleView: () -> Unit,
    staticMedia: Boolean?,
    favourites: Set<Long>,
    downloadedOnly: Boolean,
) {
    if (view == LibraryView.SHELF) {
        ShelfLibrary(platforms, games, focus, sync, downloads, notice, coverModel, artModel, emulatorFor, onSelectPlatform, onSelectGame, onToggleView, staticMedia, favourites, downloadedOnly)
        return
    }
    val colors = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PlatformRail(
            names = platforms.mapIndexed { i, p -> consoleLabel(p, shown = games.size.takeIf { downloadedOnly && i == focus.platformIndex }) },
            selected = focus.platformIndex,
            railFocused = focus.pane == LibraryPane.PLATFORMS,
            onSelect = onSelectPlatform,
        )
        Column(Modifier.fillMaxSize()) {
            val focusedGame = games.getOrNull(focus.gameIndex)?.takeIf { focus.pane == LibraryPane.GAMES }
            Header(
                title = platforms.getOrNull(focus.platformIndex)?.name ?: "Library",
                sync = sync,
                subtitle = notice ?: focusedGame?.let { gameLine(it, downloads[it.id], emulatorFor) },
                onToggleView = onToggleView,
            )
            Box(Modifier.weight(1f)) {
                if (platforms.isEmpty()) {
                    EmptyLibrary(sync)
                } else if (downloadedOnly && games.isEmpty() && (platforms.getOrNull(focus.platformIndex)?.id ?: 0) > 0) {
                    NothingDownloaded()
                } else {
                    GameGrid(
                        games = games,
                        downloads = downloads,
                        focusedIndex = focus.gameIndex.takeIf { focus.pane == LibraryPane.GAMES },
                        coverModel = coverModel,
                        onColumns = onColumns,
                        onSelect = onSelectGame,
                    )
                }
            }
            val gridGame = games.getOrNull(focus.gameIndex)
            HintRow(
                Hints.library(
                    gridGame, gridGame?.let { downloads[it.id] }, onGames = focus.pane == LibraryPane.GAMES, emulatorFor = emulatorFor,
                    favourite = gridGame?.id in favourites,
                ),
                Modifier.padding(top = 12.dp),
            )
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

/**
 * The shelf view: the consoles along the top, one console's cases in the middle, and the focused
 * game's title, console and buttons underneath.
 */
@Composable
private fun ShelfLibrary(
    platforms: List<PlatformEntity>,
    games: List<GameEntity>,
    focus: LibraryFocus,
    sync: SyncStatus,
    downloads: Map<Long, DownloadEntity>,
    notice: String?,
    coverModel: (GameEntity) -> Any?,
    artModel: (String?) -> Any?,
    emulatorFor: (String) -> String?,
    onSelectPlatform: (Int) -> Unit,
    onSelectGame: (Int) -> Unit,
    onToggleView: () -> Unit,
    staticMedia: Boolean?,
    favourites: Set<Long>,
    downloadedOnly: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A20), colors.background)))
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        ConsoleStrip(
            platforms = platforms,
            selected = focus.platformIndex,
            shown = games.size.takeIf { downloadedOnly },
            stripFocused = focus.pane == LibraryPane.PLATFORMS,
            sync = sync,
            onSelect = onSelectPlatform,
            onToggleView = onToggleView,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                platforms.isEmpty() -> EmptyLibrary(sync)
                // Under "downloaded only", a console with nothing shown has nothing downloaded;
                // otherwise an empty list (before the first query returns) draws nothing.
                games.isEmpty() -> if (downloadedOnly) platforms.getOrNull(focus.platformIndex)?.takeIf { it.id > 0 }?.let { NothingDownloaded() }
                else -> GameShelf(
                    games = games,
                    focusedIndex = focus.gameIndex,
                    active = focus.pane == LibraryPane.GAMES,
                    downloads = downloads,
                    coverModel = coverModel,
                    artModel = artModel,
                    onSelect = onSelectGame,
                    staticMedia = staticMedia,
                    shelfId = platforms.getOrNull(focus.platformIndex)?.id ?: 0L,
                )
            }
        }
        val game = games.getOrNull(focus.gameIndex)
        HintBar(
            game = game,
            console = game?.let { ContinuePlaying.consoleOf(it, platforms, platforms.getOrNull(focus.platformIndex)) }
                ?: platforms.getOrNull(focus.platformIndex)?.name.orEmpty(),
            download = game?.let { downloads[it.id] },
            notice = notice,
            onShelf = focus.pane == LibraryPane.GAMES,
            emulatorFor = emulatorFor,
            favourite = game?.id in favourites,
        )
    }
}

/**
 * The consoles: the selected one between its neighbours' names, with L1 and R1 either side, and
 * the sync state and the grid button on the right.
 */
@Composable
private fun ConsoleStrip(
    platforms: List<PlatformEntity>,
    selected: Int,
    /** How many of the selected console's games are on the shelf, when a filter leaves out some (else null). */
    shown: Int?,
    stripFocused: Boolean,
    sync: SyncStatus,
    onSelect: (Int) -> Unit,
    onToggleView: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        ButtonGlyph(PadButton.L1)
        Row(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            platforms.getOrNull(selected - 1)?.let { prev ->
                NeighbourConsole(prev.name, Modifier.weight(1f, fill = false)) { onSelect(selected - 1) }
            }
            platforms.getOrNull(selected)?.let { current ->
                val shape = RoundedCornerShape(8.dp)
                Text(
                    consoleLabel(current, shown),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .background(if (stripFocused) colors.primary.copy(alpha = 0.18f) else Color.Transparent, shape)
                        .border(2.dp, if (stripFocused) colors.primary else Color.Transparent, shape)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            platforms.getOrNull(selected + 1)?.let { next ->
                NeighbourConsole(next.name, Modifier.weight(1f, fill = false)) { onSelect(selected + 1) }
            }
        }
        ButtonGlyph(PadButton.R1)
        // What the pad does anywhere in the library; the focused game's own hints are under the shelf.
        // While a sync runs, its state stands in for them; after one failed, it comes first and Y retries.
        if (sync is SyncStatus.Running || sync is SyncStatus.Failed) {
            Text(
                if (sync is SyncStatus.Running) "Syncing ${sync.platform}…" else "Sync failed",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (sync !is SyncStatus.Running) {
            HintRow(Hints.libraryAnywhere(failed = sync is SyncStatus.Failed), Modifier.padding(start = 16.dp))
        }
        ViewToggle(LibraryView.GRID, onToggleView, Modifier.padding(start = 12.dp))
    }
}

/**
 * A console's name and how many games it has: RomM's count, or [shown] (what's on the shelf)
 * while "Downloaded only" leaves some out. Shelves of Genkan's own (no RomM id) have no count.
 */
internal fun consoleLabel(platform: PlatformEntity, shown: Int? = null): String =
    if (platform.id < 0) platform.name else "${platform.name}  ${shown ?: platform.romCount}"

@Composable
private fun NeighbourConsole(name: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        name,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

/** The button that switches to [target]; Select does the same from the pad. */
@Composable
private fun ViewToggle(target: LibraryView, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ButtonGlyph(PadButton.SELECT)
        Text(if (target == LibraryView.GRID) "Grid" else "Shelf", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
    }
}

/** Under the shelf: the focused game's title and console, and what A, X and Y do. */
@Composable
private fun HintBar(
    game: GameEntity?,
    console: String,
    download: DownloadEntity?,
    notice: String?,
    onShelf: Boolean,
    emulatorFor: (String) -> String?,
    favourite: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                game?.name ?: " ",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                notice ?: game?.let { shelfStatus(it, console, download, emulatorFor) } ?: console,
                style = MaterialTheme.typography.bodyMedium,
                color = if (notice != null) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HintRow(Hints.library(game, download, onGames = onShelf, emulatorFor = emulatorFor, favourite = favourite), Modifier.padding(start = 16.dp))
    }
}

/**
 * The line under the title: the console, the year, the size and whether the game is here (and
 * playable: with no emulator for its console, A has nothing to offer, so this line says why).
 */
private fun shelfStatus(game: GameEntity, console: String, download: DownloadEntity?, emulatorFor: (String) -> String?): String {
    if (LocalShelves.isLocal(game)) return console
    val state = when (download?.state) {
        null -> "not downloaded"
        DownloadState.QUEUED -> "queued"
        DownloadState.RUNNING -> {
            val percent = if (download.bytesTotal > 0) download.bytesDone * 100 / download.bytesTotal else 0
            "downloading $percent%"
        }
        DownloadState.INSTALLED -> if (emulatorFor(game.platformSlug) != null) "on this device" else "on this device, no emulator installed"
        DownloadState.FAILED -> "failed: ${download.error ?: "unknown error"}"
    }
    return listOfNotNull(console, game.releaseYear?.toString(), formatSize(game.fsSizeBytes), state).joinToString("  ·  ")
}

@Composable
private fun Header(title: String, sync: SyncStatus, subtitle: String?, onToggleView: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.primary)
            Text(
                subtitle ?: " ",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // What the pad does anywhere in the library, as on the shelf's console strip; the
            // focused game's own hints are under the grid. While a sync runs, its state stands in.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                syncLabel(sync).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (sync !is SyncStatus.Running) HintRow(Hints.libraryAnywhere(failed = sync is SyncStatus.Failed))
            }
            ViewToggle(LibraryView.SHELF, onToggleView)
        }
    }
}

@Composable
private fun GameGrid(
    games: List<GameEntity>,
    downloads: Map<Long, DownloadEntity>,
    focusedIndex: Int?,
    coverModel: (GameEntity) -> Any?,
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
                GameCard(game, coverModel(game), downloads[game.id], focused = index == focusedIndex, onClick = { onSelect(index) })
            }
        }
    }
}

@Composable
private fun GameCard(game: GameEntity, cover: Any?, download: DownloadEntity?, focused: Boolean, onClick: () -> Unit) {
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
                val isIcon = game.platformSlug == LocalShelves.APPS_SLUG
                AsyncImage(
                    model = cover,
                    contentDescription = game.name,
                    contentScale = if (isIcon) ContentScale.Fit else ContentScale.Crop,
                    modifier = if (isIcon) Modifier.fillMaxSize().padding(28.dp) else Modifier.fillMaxSize(),
                )
            }
            DownloadBadge(download, Modifier.align(Alignment.BottomCenter))
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

/** Progress bar while queued or downloading, a check mark once the game is on the device. */
@Composable
private fun DownloadBadge(download: DownloadEntity?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    when (download?.state) {
        null -> Unit
        DownloadState.INSTALLED -> Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(colors.primary),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = Color.Black, style = MaterialTheme.typography.titleSmall) }
        }
        DownloadState.FAILED -> Box(modifier.fillMaxWidth().background(Color(0xCC8B1A1A)).padding(6.dp)) {
            Text("Failed", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        DownloadState.QUEUED, DownloadState.RUNNING -> {
            val fraction = if (download.bytesTotal > 0) download.bytesDone.toFloat() / download.bytesTotal else 0f
            Box(modifier.fillMaxWidth().height(8.dp).background(Color(0x99000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(colors.primary))
            }
        }
    }
}

private fun gameLine(game: GameEntity, download: DownloadEntity?, emulatorFor: (String) -> String?): String {
    if (LocalShelves.isLocal(game)) return game.name
    val size = formatSize(game.fsSizeBytes)
    val state = when (download?.state) {
        null -> size
        DownloadState.QUEUED -> "queued"
        DownloadState.RUNNING -> {
            val percent = if (download.bytesTotal > 0) download.bytesDone * 100 / download.bytesTotal else 0
            "downloading $percent% of $size"
        }
        DownloadState.INSTALLED -> if (emulatorFor(game.platformSlug) != null) size else "$size  ·  no emulator installed"
        DownloadState.FAILED -> "failed: ${download.error ?: "unknown error"}"
    }
    return "${game.name}  ·  $state"
}

/** What a console shows when "downloaded only" leaves nothing of it. */
@Composable
private fun NothingDownloaded() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Nothing from this console is on the device.\nL2: sort & filter",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The "Sort & filter" panel over the library's top right: the sort and "downloaded only", the
 * focused row ([row]) marked. Taps work too: a row is changed by tapping it, Close shuts it.
 */
@Composable
internal fun OrderPanelCard(order: LibraryOrder, row: Int, onOrder: (LibraryOrder) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .padding(top = 64.dp, end = 24.dp)
            .width(360.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.onSurface.copy(alpha = 0.12f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sort & filter", style = MaterialTheme.typography.titleMedium, color = colors.primary)
        val rows = listOf("Sort" to order.sort.label, "Downloaded only" to if (order.downloadedOnly) "On" else "Off")
        rows.forEachIndexed { index, (name, value) ->
            val focused = index == row
            Row(
                Modifier
                    .fillMaxWidth()
                    // The shape drawn, not clipped to: a Sort arrow's touch area reaches past the row.
                    .background(if (focused) colors.primary.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .border(2.dp, if (focused) colors.primary else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOrder(OrderPanel.change(order, index, 1)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                if (index == 0) {
                    // Each arrow steps its own way (the rest of the row steps on, as A does).
                    SortArrow("‹", Modifier.padding(horizontal = 8.dp)) { onOrder(OrderPanel.change(order, index, -1)) }
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                    SortArrow("›", Modifier.padding(start = 8.dp)) { onOrder(OrderPanel.change(order, index, 1)) }
                } else {
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HintRow(Hints.orderPanel, Modifier.weight(1f))
            Text(
                "Close",
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
    }
}

/**
 * One of the Sort row's arrows, drawn as before ([modifier]: its spacing) but taking taps over
 * at least [ARROW_TARGET] square around it, past its glyph and the row's padding.
 */
@Composable
private fun SortArrow(glyph: String, modifier: Modifier, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.layout { measurable, constraints ->
            // Laid out at the glyph's size, so the row doesn't move; the touch area grows past it.
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Box(
            Modifier
                .matchParentSize()
                .wrapContentSize(unbounded = true)
                .size(ARROW_TARGET)
                .testTag("sort-arrow-$glyph")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
    }
}

/** The least a Sort arrow takes taps over, either way (Material's minimum touch target). */
private val ARROW_TARGET = 48.dp

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
    is SyncStatus.Done -> ""
    is SyncStatus.Failed -> "Sync failed"
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "—"
}
