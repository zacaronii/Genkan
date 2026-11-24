package app.genkan.anki

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A dictionary entry's definitions as a card's HTML, as Amatsubame's StructuredContentRenderer
 * writes them (which builds the DOM as Yomitan's StructuredContentGenerator does), so cards mined
 * in either app are the same: `gloss-sc-*` classes, `data-sc-*` attributes, the styles Yomitan
 * allows, inside `<div class="gloss-content">`. Dictionary images are left out: they live in the
 * app, not in Anki, and would show as broken.
 */
object CardHtml {

    /** A term's definitions (the glossary JSON array); several definitions become a list. */
    fun render(glossaryJson: String): String = renderOrNull(glossaryJson) ?: EMPTY

    /** What [render] gives for definitions that render nothing (an image alone). */
    const val EMPTY = "<ol class=\"gloss-list\"></ol>"

    /** [render]'s HTML, or null when no definition renders anything. */
    fun renderOrNull(glossaryJson: String): String? {
        val definitions = try {
            JSONTokener(glossaryJson).nextValue()
        } catch (e: JSONException) {
            return "<div class=\"gloss-content\">${escape(glossaryJson)}</div>"
        }
        val items = (definitions as? JSONArray)?.let { array -> (0 until array.length()).map { array.get(it) } } ?: listOf(definitions)
        val rendered = items.map(::definition).filter { it.isNotEmpty() }
        if (rendered.isEmpty()) return null
        if (rendered.size == 1) return "<div class=\"gloss-content\">${rendered[0]}</div>"
        return "<ol class=\"gloss-list\">" + rendered.joinToString("") { "<li class=\"gloss-item\"><div class=\"gloss-content\">$it</div></li>" } + "</ol>"
    }

    private fun definition(definition: Any?): String = when {
        definition is String -> escape(definition)
        // Format 3's deinflection definition: [base form, [rules]].
        definition is JSONArray && definition.opt(0) is String -> "<span class=\"gloss-deinflection\">→ ${escape(definition.getString(0))}</span>"
        definition !is JSONObject -> ""
        definition.optString("type") == "text" -> escape(definition.optString("text"))
        definition.optString("type") == "structured-content" -> "<span class=\"structured-content\">${content(definition.opt("content"))}</span>"
        else -> ""
    }

    private fun content(value: Any?): String = when (value) {
        is String -> escape(value)
        is JSONArray -> (0 until value.length()).joinToString("") { content(value.get(it)) }
        is JSONObject -> element(value)
        else -> ""
    }

    private val SIMPLE_TAGS = setOf("div", "span", "ol", "ul", "li", "details", "summary")
    private val TABLE_PARTS = setOf("thead", "tbody", "tfoot", "tr")

    private fun element(node: JSONObject): String {
        val tag = node.optString("tag").ifEmpty { return "" }
        return when (tag) {
            "br" -> "<br class=\"gloss-sc-br\">"
            "ruby", "rt", "rp" -> wrap(tag, node, styled = false)
            "table" -> "<div class=\"gloss-sc-table-container\">${wrap(tag, node, styled = false)}</div>"
            in TABLE_PARTS -> wrap(tag, node, styled = false)
            "th", "td" -> {
                var extra = ""
                (node.opt("colSpan") as? Int)?.let { extra += " colspan=\"$it\"" }
                (node.opt("rowSpan") as? Int)?.let { extra += " rowspan=\"$it\"" }
                wrap(tag, node, styled = true, extra)
            }
            in SIMPLE_TAGS -> wrap(tag, node, styled = true)
            // Not on a card: the image is the app's, not Anki's.
            "img" -> ""
            "a" -> link(node)
            else -> content(node.opt("content"))
        }
    }

    private fun wrap(tag: String, node: JSONObject, styled: Boolean, extraAttributes: String = ""): String {
        var attributes = "class=\"gloss-sc-$tag\"$extraAttributes"
        (node.opt("lang") as? String)?.let { attributes += " lang=\"${escape(it)}\"" }
        (node.opt("data") as? JSONObject)?.let { data ->
            for (key in data.keys().asSequence().sorted()) {
                if (key.isEmpty()) continue
                val name = "sc" + key.take(1).uppercase() + key.drop(1)
                attributes += " data-${kebab(name)}=\"${escape(data.get(key).toString())}\""
            }
        }
        if (styled) {
            (node.opt("style") as? JSONObject)?.let { style -> css(style).takeIf { it.isNotEmpty() }?.let { attributes += " style=\"${escape(it)}\"" } }
            (node.opt("title") as? String)?.let { attributes += " title=\"${escape(it)}\"" }
            if (node.opt("open") == true) attributes += " open"
        }
        return "<$tag $attributes>${content(node.opt("content"))}</$tag>"
    }

    private val PASSTHROUGH = listOf(
        "fontStyle", "fontWeight", "fontSize", "color", "background", "backgroundColor", "verticalAlign",
        "textAlign", "textEmphasis", "textShadow", "textDecorationStyle", "textDecorationColor", "borderColor",
        "borderStyle", "borderRadius", "borderWidth", "clipPath", "margin", "padding", "paddingTop",
        "paddingLeft", "paddingRight", "paddingBottom", "wordBreak", "whiteSpace", "cursor", "listStyleType",
    )

    /** The style properties Yomitan allows; numeric margins are in em. */
    fun css(style: JSONObject): String {
        val declarations = mutableListOf<String>()
        for (key in PASSTHROUGH) (style.opt(key) as? String)?.let { declarations += "${kebab(key)}: $it" }
        when (val line = style.opt("textDecorationLine")) {
            is String -> declarations += "text-decoration: $line"
            is JSONArray -> declarations += "text-decoration: " + (0 until line.length()).joinToString(" ") { line.optString(it) }
        }
        for (key in listOf("marginTop", "marginLeft", "marginRight", "marginBottom")) {
            when (val value = style.opt(key)) {
                is String -> declarations += "${kebab(key)}: $value"
                is Number -> declarations += "${kebab(key)}: ${number(value.toDouble())}em"
            }
        }
        return declarations.joinToString("; ")
    }

    private fun link(node: JSONObject): String {
        val href = node.optString("href")
        val text = "<span class=\"gloss-link-text\">${content(node.opt("content"))}</span>"
        val lang = (node.opt("lang") as? String)?.let { " lang=\"${escape(it)}\"" }.orEmpty()
        if (href.startsWith("?")) {
            // A lookup link, e.g. "?query=食べる&wildcards=off".
            val query = href.drop(1).split('&').firstOrNull { it.startsWith("query=") }?.substringAfter('=')
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
            return "<a class=\"gloss-link\" data-external=\"false\" data-lookup=\"${escape(query)}\"$lang>$text</a>"
        }
        return "<a class=\"gloss-link\" data-external=\"true\" href=\"${escape(href)}\"$lang>$text</a>"
    }

    /** A whole number as is, else four significant digits without trailing zeros (C's %.4g, as Amatsubame's). */
    private fun number(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString()
        else java.math.BigDecimal(value).round(java.math.MathContext(4)).stripTrailingZeros().toPlainString()

    /** HTML-escaped as Amatsubame does it, the apostrophe included. */
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** `someKey` → `some-key`, as the DOM dataset API maps names to attributes. */
    fun kebab(name: String): String = buildString { for (c in name) if (c.isUpperCase()) append('-').append(c.lowercaseChar()) else append(c) }
}
