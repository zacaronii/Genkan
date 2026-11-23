package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.Favourites
import app.genkan.library.GameEntity

/**
 * A button on the Thor's pad, as the hints draw it. The face buttons carry the letters of the
 * Android keys they send (the Thor's key layout maps them one to one, with AYN's "flip button
 * layout" off); the rest are named.
 */
enum class PadButton(val label: String, val face: Boolean = false) {
    A("A", face = true),
    B("B", face = true),
    X("X", face = true),
    Y("Y", face = true),
    L1("L1"),
    R1("R1"),
    L2("L2"),
    R2("R2"),
    START("Start"),
    SELECT("Select"),
    L3("L3"),
    R3("R3"),
}

/** One hint: what [buttons] (pressed alone, or either of them) do here. */
data class ButtonHint(val buttons: List<PadButton>, val action: String) {
    constructor(button: PadButton, action: String) : this(listOf(button), action)
}

/** A face button as a round key, the others as a pill. Every screen's hints use these. */
@Composable
fun ButtonGlyph(button: PadButton, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = if (button.face) Modifier.size(20.dp).clip(CircleShape) else Modifier.height(20.dp).clip(RoundedCornerShape(10.dp))
    Box(
        modifier
            .then(shape)
            .background(colors.onSurface.copy(alpha = 0.14f))
            .then(if (button.face) Modifier else Modifier.padding(horizontal = 6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = colors.onSurface)
    }
}

/** A row of hints, each its glyphs then its action, as every screen shows what the pad does. */
@Composable
fun HintRow(hints: List<ButtonHint>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (hint in hints) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                hint.buttons.forEach { ButtonGlyph(it) }
                Text(
                    hint.action,
                    Modifier.padding(start = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * What the pad does on each screen, for its hint row: only what the focused thing can really do,
 * so a game that would download never says "Play", and a key that would do nothing isn't shown.
 */
object Hints {

    /**
     * The library, shelf or grid: what the pad does to the focused game. On the console strip
     * ([onGames] false) A goes down to the games; on a game it acts on it, X deletes an installed
     * one, R2 marks a RomM game as a [favourite] or unmarks it, and B goes back to the strip. What
     * works anywhere in the library is [libraryAnywhere].
     */
    fun library(
        game: GameEntity?,
        download: DownloadEntity?,
        onGames: Boolean,
        emulatorFor: (String) -> String?,
        favourite: Boolean = false,
        /** The focused game's full box can be turned round (it has a back), and whether it is now. */
        canTurn: Boolean = false,
        turned: Boolean = false,
    ): List<ButtonHint> =
        buildList {
            if (game != null && onGames) {
                primary(game, download, emulatorFor)?.let { add(ButtonHint(PadButton.A, it)) }
                if (download?.state == DownloadState.INSTALLED) add(ButtonHint(PadButton.X, "Delete"))
                if (Favourites.canMark(game)) add(ButtonHint(PadButton.R2, if (favourite) "Unfavourite" else "Favourite"))
                if (canTurn) add(ButtonHint(PadButton.R3, if (turned) "Box front" else "Box back"))
                add(ButtonHint(PadButton.B, "Back"))
            } else if (game != null) {
                add(ButtonHint(PadButton.A, "Choose"))
            }
        }

    /** L3 flips "Downloaded only" ([downloadedOnly] now): the hint says what it would switch to. */
    fun downloadedFilter(downloadedOnly: Boolean): ButtonHint =
        ButtonHint(PadButton.L3, if (downloadedOnly) "All games" else "Downloaded")

    /**
     * What works anywhere in the library, whatever is focused. After a sync that [failed], Y
     * retries it (and Settings is where the server's address is fixed).
     */
    fun libraryAnywhere(failed: Boolean = false): List<ButtonHint> = listOf(
        ButtonHint(PadButton.L2, "Sort"),
        ButtonHint(PadButton.Y, if (failed) "Retry" else "Sync"),
        ButtonHint(PadButton.START, "Settings"),
    )

    /** The "Sort & filter" panel: A and right step the focused row, left steps back, B or L2 closes. */
    val orderPanel: List<ButtonHint> = listOf(
        ButtonHint(PadButton.A, "Change"),
        ButtonHint(listOf(PadButton.B, PadButton.L2), "Close"),
    )

    /** What A does on [game], or null when it can't start it (no emulator for its console). */
    fun primary(game: GameEntity, download: DownloadEntity?, emulatorFor: (String) -> String?): String? {
        when (game.platformSlug) {
            LocalShelves.PC_SLUG -> return "Play in GameNative"
            LocalShelves.APPS_SLUG -> return "Open"
        }
        return when (download?.state) {
            null -> "Download"
            DownloadState.QUEUED, DownloadState.RUNNING -> "Cancel"
            DownloadState.INSTALLED -> emulatorFor(game.platformSlug)?.let { "Play in $it" }
            DownloadState.FAILED -> "Retry"
        }
    }

    /**
     * Settings. On the group list A opens the group and B leaves; on a group's rows A runs the
     * focused row ([rowAction], as its switch or button says) and B goes back to the groups.
     */
    fun settings(onRows: Boolean, rowAction: String?): List<ButtonHint> =
        if (onRows) {
            listOfNotNull(
                rowAction?.let { ButtonHint(PadButton.A, it) },
                ButtonHint(PadButton.B, "Groups"),
                ButtonHint(PadButton.START, "Library"),
            )
        } else {
            listOf(ButtonHint(PadButton.A, "Open"), ButtonHint(listOf(PadButton.B, PadButton.START), "Library"))
        }

    /** A settings page of fields and buttons: the D-pad moves between them, A presses the focused one. */
    fun form(): List<ButtonHint> = listOf(
        ButtonHint(PadButton.A, "Select"),
        ButtonHint(PadButton.B, "Back"),
        ButtonHint(PadButton.START, "Library"),
    )

    /**
     * The dictionaries page. On the import row A imports; on a dictionary A turns it on or off,
     * L1 and R1 move it in the popup's order, and X removes it (asked for twice: [removalArmed]
     * when the next X removes it).
     */
    fun dictionaries(onImport: Boolean, enabled: Boolean, removalArmed: Boolean): List<ButtonHint> = buildList {
        if (onImport) {
            add(ButtonHint(PadButton.A, "Import"))
        } else {
            add(ButtonHint(PadButton.A, if (enabled) "Turn off" else "Turn on"))
            add(ButtonHint(listOf(PadButton.L1, PadButton.R1), "Move up / down"))
            add(ButtonHint(PadButton.X, if (removalArmed) "Remove (press again)" else "Remove"))
        }
        add(ButtonHint(PadButton.B, "Back"))
    }

    /** What A does on a settings row: a switch says which way it turns, a button its own name. */
    fun settingsRow(title: String, switchOn: Boolean?): String =
        when (switchOn) {
            null -> title
            true -> "Turn off"
            false -> "Turn on"
        }
}
