package app.genkan.dictionary

import com.squareup.moshi.JsonReader
import okio.Buffer

/**
 * A dictionary entry's definitions as plain text, for compact views. Yomitan glossaries are a
 * JSON array of strings or structured content; in structured content the definitions are the
 * items of a list marked `data.content = "glossary"` (JMdict), else all of its text.
 */
object GlossaryText {

    fun definitions(glossaryJson: String): List<String> {
        val root = try {
            JsonReader.of(Buffer().writeUtf8(glossaryJson)).readJsonValue()
        } catch (e: Exception) {
            return listOf(glossaryJson)
        }
        val items = (root as? List<*>) ?: listOf(root)
        return items.flatMap { item ->
            when (item) {
                is String -> listOf(item)
                is Map<*, *> -> when (item["type"]) {
                    "structured-content" -> structured(item["content"])
                    "text" -> listOfNotNull(item["text"] as? String)
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun structured(content: Any?): List<String> {
        val marked = ArrayList<String>()
        collectMarked(content, inGlossary = false, out = marked)
        if (marked.isNotEmpty()) return marked
        return listOf(text(content))
    }

    /** The text of each list item inside a node marked as the glossary. */
    private fun collectMarked(node: Any?, inGlossary: Boolean, out: MutableList<String>) {
        when (node) {
            is List<*> -> node.forEach { collectMarked(it, inGlossary, out) }
            is Map<*, *> -> {
                val marked = inGlossary || (node["data"] as? Map<*, *>)?.get("content") == "glossary"
                if (marked && node["tag"] == "li") out += text(node["content"]) else collectMarked(node["content"], marked, out)
            }
        }
    }

    private fun text(node: Any?): String = when (node) {
        is String -> node
        is List<*> -> node.joinToString("") { text(it) }
        is Map<*, *> -> if (node["tag"] == "rt" || node["tag"] == "img") "" else text(node["content"])
        else -> ""
    }
}
