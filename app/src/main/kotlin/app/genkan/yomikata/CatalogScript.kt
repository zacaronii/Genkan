package app.genkan.yomikata

import org.json.JSONObject

/**
 * A release's Yomikata script as the catalog's index lists it (`scripts`, keyed by release id like
 * `entries`): where its bundled source is, its hash, the builds it was checked on, the regions it
 * needs, the runtime level it needs, and its data files by name.
 */
data class CatalogScript(
    val id: String,
    val path: String,
    val sha256: String,
    val platform: String,
    val builds: List<String>,
    val needs: List<String>,
    /** The script feature level it needs (yk_script_level): a runtime below it can't run it. */
    val runtime: Int,
    /** Data files by name (`reader.data(name)`): each one's path and hash. */
    val data: Map<String, Pair<String, String>>,
) {
    /**
     * Why Genkan can't run it here, or null when it can: a runtime level above [level], a build
     * [buildId] (when known) that isn't one of [builds], or a region in [needs] this host lacks
     * ([regions]).
     */
    fun unusable(level: Int, buildId: String?, regions: Set<String>): String? = when {
        runtime > level -> "it needs reader runtime $runtime, this Genkan has $level"
        buildId != null && builds.isNotEmpty() && buildId !in builds -> "it wasn't checked on build $buildId (${builds.joinToString()})"
        needs.any { it !in regions } -> "it needs ${needs.filter { it !in regions }.joinToString()}, which this game doesn't give"
        else -> null
    }

    /**
     * The script's source and every data file, each through [file] (a path and the index's hash to
     * its bytes, or an IOException): Found with all of them, Failed when any is missing. A script
     * without its data can't run, so it isn't offered at all and the game takes its JSON layout.
     */
    inline fun files(file: (path: String, sha256: String) -> ByteArray): ScriptLookup = try {
        val source = file(path, sha256)
        ScriptLookup.Found(ScriptFiles(this, source, data.mapValues { (_, f) -> file(f.first, f.second) }))
    } catch (e: IndexBehind) {
        ScriptLookup.Failed(e.message ?: e.javaClass.simpleName, indexBehind = true)
    } catch (e: java.io.IOException) {
        ScriptLookup.Failed(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        fun parse(id: String, record: JSONObject): CatalogScript {
            val data = record.optJSONObject("data")
            return CatalogScript(
                id = id,
                path = record.getString("path"),
                sha256 = record.getString("sha256"),
                platform = record.optString("platform"),
                builds = record.optJSONArray("builds").strings(),
                needs = record.optJSONArray("needs").strings(),
                runtime = record.optInt("runtime", 1),
                data = data?.keys()?.asSequence()?.associateWith { name ->
                    data.getJSONObject(name).let { it.getString("path") to it.getString("sha256") }
                }.orEmpty(),
            )
        }

        private fun org.json.JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}

/** A script with its source and data files, from the device's copy of the catalog. */
class ScriptFiles(val script: CatalogScript, val source: ByteArray, val data: Map<String, ByteArray>)

/** What the catalog has as a script for a release id. */
sealed interface ScriptLookup {
    data class Found(val files: ScriptFiles) : ScriptLookup
    data object NotListed : ScriptLookup
    /** [indexBehind]: a file didn't match the index's hash, so the device's index may be older than the catalog. */
    data class Failed(val reason: String, val indexBehind: Boolean = false) : ScriptLookup
}

/** A catalog file that doesn't match the hash the device's index gives for it: the file moved on since. */
class IndexBehind(message: String) : java.io.IOException(message)

/**
 * When the device's copy of the index is fetched again before [YomikataCatalog]'s usual twelve
 * hours: a game asked for an id it doesn't list (or a file it lists changed), and the copy is
 * older than [STALE_MS], so a script or entry added since isn't missed for half a day. At most
 * once every [STALE_MS], so an id the catalog really hasn't, or being offline, costs one request.
 */
object IndexAge {
    const val STALE_MS = 10 * 60 * 1000L

    fun fetchAgain(nowMs: Long, fetchedAtMs: Long, lastTriedMs: Long): Boolean =
        nowMs - fetchedAtMs > STALE_MS && nowMs - lastTriedMs > STALE_MS

    /**
     * Whether [lookup] says the device's index may be behind the catalog: the id is in none of its
     * lists ([listed]: it has an entry), or a file it lists changed. An entry without a script is
     * a game nobody has written one for yet, not a sign of an old index.
     */
    fun mayBeBehind(lookup: ScriptLookup, catalogSet: Boolean, listed: Boolean): Boolean = when (lookup) {
        ScriptLookup.NotListed -> catalogSet && !listed
        is ScriptLookup.Failed -> lookup.indexBehind
        is ScriptLookup.Found -> false
    }
}
