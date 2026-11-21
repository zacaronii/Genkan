package app.genkan.library

import android.util.Log
import app.genkan.romm.RomMApi
import app.genkan.romm.RomMPlatform
import app.genkan.romm.RomMRom
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMUnauthorizedException
import app.genkan.romm.RomMUtils
import app.genkan.romm.describeFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.genkan.romm.RomMApiFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Running(val platform: String, val gamesDone: Int) : SyncStatus()
    data class Done(val games: Int) : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
}

/** Mirrors the RomM library into Room. A full pass per platform; the server's lists are small. */
@Singleton
class LibrarySync @Inject constructor(
    private val session: RomMSession,
    private val dao: LibraryDao,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun sync() {
        if (mutex.isLocked) return
        mutex.withLock {
            val api = session.api() ?: run {
                _status.value = SyncStatus.Failed("Not signed in to RomM"); return
            }
            try {
                _status.value = SyncStatus.Done(syncWith(api))
            } catch (e: RomMUnauthorizedException) {
                _status.value = SyncStatus.Failed("Signed out by RomM")
                session.onUnauthorized()
            } catch (e: Exception) {
                Log.w(TAG, "Library sync failed: $e", e)
                _status.value = SyncStatus.Failed(describeFailure(e, "Library sync failed"))
            }
        }
    }

    suspend fun clear() {
        dao.clearGames()
        dao.clearPlatforms()
        _status.value = SyncStatus.Idle
    }

    private suspend fun syncWith(api: RomMApi): Int {
        val platforms = api.getPlatforms().bodyOrThrow("platforms")
        dao.upsertPlatforms(platforms.map(::toEntity))
        dao.deletePlatformsExcept(platforms.map { it.id })
        dao.deleteGamesOutside(platforms.map { it.id })

        var total = 0
        for (platform in platforms.filter { it.romCount > 0 }) {
            val games = mutableListOf<GameEntity>()
            var offset = 0
            do {
                _status.value = SyncStatus.Running(platform.displayName ?: platform.name, total + games.size)
                val page = api.getRoms(pageParams(platform.id, offset)).bodyOrThrow("roms for ${platform.slug}")
                games += page.items.map(::toEntity)
                offset += page.items.size
            } while (page.items.size == PAGE_SIZE && offset < (page.total ?: Int.MAX_VALUE))
            dao.replaceGames(platform.id, games)
            total += games.size
        }
        Log.i(TAG, "Synced ${platforms.size} platforms, $total games")
        return total
    }

    private fun <T> retrofit2.Response<T>.bodyOrThrow(what: String): T {
        if (code() == 401) throw RomMUnauthorizedException()
        return body().takeIf { isSuccessful } ?: error("RomM refused $what (${code()})")
    }

    companion object {
        private const val TAG = "LibrarySync"

        /** Argosy's page size against RomM. */
        const val PAGE_SIZE = 100

        fun pageParams(platformId: Long, offset: Int): Map<String, String> = mapOf(
            "platform_ids" to platformId.toString(),
            "limit" to PAGE_SIZE.toString(),
            "offset" to offset.toString(),
            "order_by" to "name",
            "order_dir" to "asc",
        )

        fun toEntity(platform: RomMPlatform) = PlatformEntity(
            id = platform.id,
            slug = platform.slug,
            fsSlug = platform.fsSlug,
            name = platform.customName?.takeIf { it.isNotBlank() } ?: platform.displayName ?: platform.name,
            romCount = platform.romCount,
            logoPath = platform.logoUrl,
        )

        fun toEntity(rom: RomMRom) = GameEntity(
            id = rom.id,
            platformId = rom.platformId,
            platformSlug = rom.platformSlug,
            name = rom.name,
            sortTitle = RomMUtils.createSortTitle(rom.name),
            fsName = rom.fileName,
            fsSizeBytes = rom.fileSize,
            coverPath = rom.coverSmall ?: rom.coverUrl,
            hasMultipleFiles = rom.hasMultipleFiles,
            summary = rom.summary?.trim()?.takeIf { it.isNotEmpty() },
            genres = rom.genres?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            releaseYear = rom.firstReleaseDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year },
            developer = rom.developerName,
            coverLargePath = rom.coverLarge,
            physicalPath = RomMApiFactory.resourcePath(rom.ssMetadata?.physicalPath),
            logoPath = RomMApiFactory.resourcePath(rom.ssMetadata?.logoPath),
            box2dPath = RomMApiFactory.resourcePath(rom.ssMetadata?.box2dPath),
            box2dSidePath = RomMApiFactory.resourcePath(rom.ssMetadata?.box2dSidePath),
            box2dBackPath = RomMApiFactory.resourcePath(rom.ssMetadata?.box2dBackPath),
            box3dPath = RomMApiFactory.resourcePath(rom.ssMetadata?.box3dPath),
            titleScreenPath = RomMApiFactory.resourcePath(rom.ssMetadata?.titleScreenPath),
            marqueePath = RomMApiFactory.resourcePath(rom.ssMetadata?.marqueePath),
            fanartPath = RomMApiFactory.resourcePath(rom.ssMetadata?.fanartPath),
            screenshotPaths = GameEntity.joinList(rom.screenshotPaths?.mapNotNull(RomMApiFactory::resourcePath)),
            manualPath = rom.manualPath.takeIf { rom.hasManual }?.let(RomMApiFactory::resourcePath),
            regions = GameEntity.joinList(rom.regions),
            franchises = GameEntity.joinList(rom.metadatum?.franchises),
            publishers = GameEntity.joinList(rom.metadatum?.publishers),
            gameModes = GameEntity.joinList(rom.metadatum?.gameModes),
            playerCount = rom.metadatum?.playerCount?.trim()?.takeIf { it.isNotEmpty() },
            averageRating = rom.metadatum?.averageRating?.takeIf { it > 0f },
            releaseDateMillis = rom.firstReleaseDateMillis,
            youtubeVideoId = rom.youtubeVideoId?.trim()?.takeIf { it.isNotEmpty() },
            discCount = discCount(rom),
            hltbId = rom.hltbId,
            // HowLongToBeat reports 0 for what it doesn't know.
            hltbMainStorySec = rom.hltbMetadata?.mainStorySec?.takeIf { it > 0 },
            hltbMainPlusExtraSec = rom.hltbMetadata?.mainPlusExtraSec?.takeIf { it > 0 },
            hltbCompletionistSec = rom.hltbMetadata?.completionistSec?.takeIf { it > 0 },
            hltbAllStylesSec = rom.hltbMetadata?.allStylesSec?.takeIf { it > 0 },
            hltbReviewScore = rom.hltbMetadata?.reviewScore?.takeIf { it > 0 },
            lastPlayedMillis = parseTimestamp(rom.romUser?.lastPlayed),
            backlogged = rom.romUser?.backlogged ?: false,
            nowPlaying = rom.romUser?.nowPlaying ?: false,
            userRating = rom.romUser?.rating ?: 0,
            userDifficulty = rom.romUser?.difficulty ?: 0,
            userCompletion = rom.romUser?.completion ?: 0,
            userStatus = rom.romUser?.status?.takeIf { it.isNotBlank() },
            userHidden = rom.romUser?.hidden ?: false,
        )

        /**
         * The number of discs, from ScreenScraper's disc cut-outs or from a folder game's disc
         * files, whichever says more. Null when neither names a disc, which a single-disc or
         * cartridge game never does.
         */
        fun discCount(rom: RomMRom): Int? {
            val ss = rom.ssMetadata
            val extraDiscs = ss?.physicalExtraDiscs.orEmpty()
            val fromSs = if (ss?.physicalDisc == null && extraDiscs.isEmpty()) {
                0
            } else {
                val numbers = listOfNotNull(ss?.physicalDisc) + extraDiscs.map { it.disc }
                maxOf(numbers.maxOrNull() ?: 0, extraDiscs.size + 1)
            }
            val fromFiles = rom.files.orEmpty().filter { it.isGameContent }.mapNotNull { it.discNumber }.distinct().size
            return maxOf(fromSs, fromFiles).takeIf { it > 0 }
        }

        /** RomM's ISO-8601 timestamps, with or without an offset (none means UTC). */
        fun parseTimestamp(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                try {
                    LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
    }
}
