package app.genkan.romm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** One save as the device holds it, for `POST /api/sync/negotiate`. */
@JsonClass(generateAdapter = true)
data class RomMClientSave(
    @Json(name = "rom_id") val romId: Long,
    @Json(name = "file_name") val fileName: String,
    /** ISO-8601 UTC, the save's last change on the device. */
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "content_hash") val contentHash: String? = null,
    @Json(name = "emulator") val emulator: String? = null,
    /** Saves pair on (rom_id, slot); a null slot always uploads as a new archival save. */
    @Json(name = "slot") val slot: String? = null,
)

@JsonClass(generateAdapter = true)
data class RomMNegotiatePayload(
    @Json(name = "device_id") val deviceId: String?,
    @Json(name = "saves") val saves: List<RomMClientSave>,
    /** Scopes the answer to these games; a game left out is never read as deleted. */
    @Json(name = "rom_ids") val romIds: List<Long>? = null,
    @Json(name = "emulators") val emulators: List<String>? = null,
)

/** What to do with one save: upload, download, conflict, delete or no_op. */
@JsonClass(generateAdapter = true)
data class RomMSyncOperation(
    @Json(name = "action") val action: String,
    @Json(name = "rom_id") val romId: Long,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "reason") val reason: String,
    @Json(name = "save_id") val saveId: Long? = null,
    @Json(name = "emulator") val emulator: String? = null,
    @Json(name = "slot") val slot: String? = null,
    @Json(name = "server_content_hash") val serverContentHash: String? = null,
    @Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class RomMNegotiateResponse(
    @Json(name = "session_id") val sessionId: Long,
    @Json(name = "operations") val operations: List<RomMSyncOperation>,
)

@JsonClass(generateAdapter = true)
data class RomMSyncCompletePayload(
    @Json(name = "operations_completed") val operationsCompleted: Int,
    @Json(name = "operations_failed") val operationsFailed: Int,
)

/** The fields of a stored save Genkan reads. */
@JsonClass(generateAdapter = true)
data class RomMSave(
    @Json(name = "id") val id: Long,
    @Json(name = "rom_id") val romId: Long,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "content_hash") val contentHash: String? = null,
    @Json(name = "emulator") val emulator: String? = null,
    @Json(name = "slot") val slot: String? = null,
)

@JsonClass(generateAdapter = true)
data class RomMDownloadedPayload(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "content_hash") val contentHash: String? = null,
)
