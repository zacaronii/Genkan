package app.genkan.ui

import app.genkan.download.DownloadEntity
import app.genkan.download.DownloadState
import app.genkan.launch.LocalShelves
import app.genkan.library.GameEntity
import app.genkan.play.PlayTotals
import app.genkan.saves.SaveStatus
import app.genkan.stats.ReadingGameTotals
import app.genkan.words.GameLookupTotals
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * The words on the bottom screen's game details, kept apart from the layout so they can be
 * tested. Every function leaves out what isn't known: an empty value gives no text at all,
 * never a dash.
 */
object GameDetailsFormat {

    /** A no-break space: a line wraps between parts, never inside "5 h 42 min" or before a dot. */
    const val NBSP = ' '

    private const val SEPARATOR = "$NBSP$NBSP·  "

    /** "42 min" under an hour, "5 h 42 min" from there, held together on one line. */
    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60_000
        return if (minutes < 60) "$minutes${NBSP}min" else "${minutes / 60}${NBSP}h$NBSP${minutes % 60}${NBSP}min"
    }

    /** Year, developer, publishers, genres, players and regions, whichever are known. */
    fun detailLine(game: GameEntity): String? {
        val developer = game.developer?.trim()?.takeIf { it.isNotEmpty() }
        // A studio that published its own game is named once.
        val publishers = GameEntity.splitList(game.publishers).map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(developer, ignoreCase = true) }
        val parts = listOfNotNull(
            releaseYear(game)?.toString(),
            developer,
            publishers.joinToString(", ").takeIf { it.isNotEmpty() },
            game.genres?.trim()?.takeIf { it.isNotEmpty() },
            players(game.playerCount),
            GameEntity.splitList(game.regions).joinToString("/").takeIf { it.isNotEmpty() },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** The first release date's year (UTC), or the older year-only column. */
    fun releaseYear(game: GameEntity): Int? = game.releaseDateMillis?.let {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }.get(Calendar.YEAR)
    } ?: game.releaseYear

    /** "1 player", "1-2 players"; a count the server words differently is shown as it is. */
    fun players(count: String?): String? {
        val text = count?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            text == "1" -> "1 player"
            text.all { it.isDigit() || it == '-' || it == '–' || it == '+' } -> "$text players"
            else -> text
        }
    }

    /** What the device has of the game: downloaded, downloading, failed or not here yet. */
    fun downloadLine(game: GameEntity, download: DownloadEntity?): String? = when (download?.state) {
        DownloadState.INSTALLED -> "Downloaded" + sizeSuffix(game.fsSizeBytes)
        DownloadState.QUEUED -> "Waiting to download"
        DownloadState.RUNNING -> "Downloading…" +
            download.takeIf { it.bytesTotal > 0 }?.let { " ${it.bytesDone * 100 / it.bytesTotal}%" }.orEmpty()
        DownloadState.FAILED -> "Download failed" + download.error?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        null -> if (LocalShelves.isLocal(game)) null else "Not downloaded" + sizeSuffix(game.fsSizeBytes)
    }

    /**
     * Play time and the last time played. The device's own sessions come first; RomM's last
     * played date stands in when the game was never played here.
     */
    fun playLine(play: PlayTotals?, rommLastPlayed: Long?): String? {
        val date = { at: Long -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)) }
        if (play != null && play.plays > 0) {
            val last = (play.lastPlayed ?: rommLastPlayed)?.let { "${SEPARATOR}last ${date(it)}" }.orEmpty()
            return "Played ${duration(play.totalMs)}$last"
        }
        return rommLastPlayed?.let { "Last played ${date(it)}" }
    }

    /** "Main story about 32 h", when HowLongToBeat has a main story time. */
    fun hltbLine(mainStorySec: Int?): String? = estimate(mainStorySec)?.let { "Main story about $it" }

    /**
     * HowLongToBeat's longer times under the main story's: "Main + extras about 45 h · everything
     * about 80 h", either alone when the other is missing, or null without both.
     */
    fun hltbMoreLine(mainPlusExtraSec: Int?, completionistSec: Int?): String? {
        val parts = listOfNotNull(
            estimate(mainPlusExtraSec)?.let { "Main + extras about $it" },
            estimate(completionistSec)?.let { if (mainPlusExtraSec.isPositive()) "everything about $it" else "Everything about $it" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    private fun Int?.isPositive() = this != null && this > 0

    /** A HowLongToBeat time in round hours, or minutes under an hour; null when there is none. */
    private fun estimate(seconds: Int?): String? {
        val s = seconds?.takeIf { it > 0 } ?: return null
        return if (s >= 3600) "${(s + 1800) / 3600}${NBSP}h" else "${s / 60}${NBSP}min"
    }

    /**
     * How far the time played goes toward HowLongToBeat's main story, from 0 to 1 (full at or
     * past it); null when there is no main story time or nothing was played.
     */
    fun hltbProgress(mainStorySec: Int?, playedMs: Long): Float? {
        val seconds = mainStorySec?.takeIf { it > 0 } ?: return null
        if (playedMs <= 0) return null
        return (playedMs / (seconds * 1000.0)).coerceAtMost(1.0).toFloat()
    }

    /** The RomM user's own status, completion, rating and flags for the game. */
    fun rommLine(game: GameEntity): String? {
        val parts = listOfNotNull(
            game.userStatus?.let(::statusLabel),
            game.userCompletion.takeIf { it > 0 }?.let { "$it% complete" },
            game.userRating.takeIf { it > 0 }?.let { "rated $it/10" },
            game.userDifficulty.takeIf { it > 0 }?.let { "difficulty $it/10" },
            "now playing".takeIf { game.nowPlaying },
            "backlogged".takeIf { game.backlogged },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR, prefix = "RomM: ")
    }

    /** RomM's status values in words; one it adds later is shown with its underscores as spaces. */
    fun statusLabel(status: String): String? {
        val key = status.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        return when (key) {
            "incomplete" -> "Unfinished"
            "finished" -> "Finished"
            "completed_100" -> "Completed 100%"
            "retired" -> "Retired"
            "never_playing" -> "Never playing"
            else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    /** "Read 48,213 characters (1,204 lines) in 3 h 10 min", once anything was read. */
    fun readingLine(reading: ReadingGameTotals): String? {
        if (reading.characters <= 0) return null
        val lines = reading.lines.takeIf { it > 0 }?.let { " (${"%,d".format(it)} lines)" }.orEmpty()
        return "Read ${"%,d".format(reading.characters)} characters$lines in ${duration(reading.activeMs)}"
    }

    /** "Looked up 120 words (153 lookups)" while reading the game; null before the first lookup. */
    fun lookupLine(lookups: GameLookupTotals): String? {
        if (lookups.words <= 0) return null
        val words = if (lookups.words == 1) "1 word" else "${"%,d".format(lookups.words)} words"
        val visits = lookups.lookups.takeIf { it > lookups.words }?.let { " (${"%,d".format(it)} lookups)" }.orEmpty()
        return "Looked up $words$visits"
    }

    /** Where the save stands with RomM; [time] formats the moment it was last in step. */
    fun saveLine(save: SaveStatus?, time: (Long) -> String = ::shortTime): String? = when (save) {
        is SaveStatus.Synced -> "Save: in step with RomM (${save.what}, ${time(save.at)})"
        is SaveStatus.Conflict -> "Save: changed here and in RomM; this copy kept and archived in RomM"
        is SaveStatus.Failed -> "Save sync failed: ${save.reason}"
        null -> null
    }

    /** The main button's words: what tapping it would do now. */
    fun primaryLabel(game: GameEntity, download: DownloadEntity?): String = when {
        game.platformSlug == LocalShelves.APPS_SLUG -> "Open"
        LocalShelves.isLocal(game) -> "Play"
        else -> when (download?.state) {
            DownloadState.INSTALLED -> "Play"
            DownloadState.QUEUED, DownloadState.RUNNING -> "Cancel download"
            DownloadState.FAILED -> "Retry download"
            null -> "Download"
        }
    }

    /** The look button's words: the game's own look, or its console's. */
    fun lookLabel(look: GameLook): String =
        look.own?.let { "Look: ${it.label}" } ?: "Look: console's (${look.console.label})"

    /** The game's own step, on the button that forgets it. */
    fun tuningLabel(note: GameTuningNote): String =
        "Reset speed (${if (note.learned) "learned" else "set to"} ${note.label})"

    private fun shortTime(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

    private fun sizeSuffix(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "$SEPARATOR%.1f GB".format(bytes / (1L shl 30).toDouble())
        // A Famicom disk or a Game Boy cartridge is kilobytes: "0 MB" would say it's empty.
        bytes >= 1L shl 20 -> "$SEPARATOR${bytes / (1L shl 20)} MB"
        else -> "$SEPARATOR${(bytes + 1023) / 1024} KB"
    }
}
