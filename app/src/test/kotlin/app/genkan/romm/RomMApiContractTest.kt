package app.genkan.romm

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Every endpoint Genkan calls must exist, with the same verb, in the OpenAPI spec of the RomM
 * release it targets (a snapshot from a RomM 5.4.0 server). Bump the snapshot with RomM.
 */
class RomMApiContractTest {

    @Suppress("UNCHECKED_CAST")
    private val paths: Map<String, Map<String, Any>> by lazy {
        val json = javaClass.getResource("/romm/openapi-5.4.0.json")!!.readText()
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val spec = Moshi.Builder().build().adapter<Map<String, Any>>(type).fromJson(json)!!
        spec["paths"] as Map<String, Map<String, Any>>
    }

    @Test
    fun `every RomMApi endpoint exists in the RomM spec`() {
        val endpoints = RomMApi::class.java.declaredMethods.mapNotNull { method ->
            method.getAnnotation(GET::class.java)?.let { "get" to it.value }
                ?: method.getAnnotation(POST::class.java)?.let { "post" to it.value }
        }
        assertTrue(endpoints.size >= 10)
        val missing = endpoints.filter { (verb, path) ->
            val specPath = "/" + path.replace("{fileName}", "{file_name}")
            paths[specPath]?.containsKey(verb) != true
        }
        assertTrue("Not in RomM 5.4.0's spec: $missing", missing.isEmpty())
    }

    @Test
    fun `the scopes Genkan asks for are scopes RomM defines`() {
        val json = javaClass.getResource("/romm/openapi-5.4.0.json")!!.readText()
        RomMSession.SCOPES.forEach { assertTrue("unknown scope $it", json.contains("\"$it\"")) }
    }
}
