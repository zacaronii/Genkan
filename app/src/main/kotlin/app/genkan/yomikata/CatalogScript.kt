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
    data class Failed(val reason: String) : ScriptLookup
}
