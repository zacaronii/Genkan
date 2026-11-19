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
import java.time.Instant
import java.time.ZoneOffset
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
        )
    }
}
