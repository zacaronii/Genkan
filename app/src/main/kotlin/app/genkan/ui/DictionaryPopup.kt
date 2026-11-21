package app.genkan.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import app.genkan.dictionary.Dictionaries
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/** What the popup page shows: a word's entries, or one kanji's. */
sealed interface PopupContent {
    /**
     * [statesJson]: each hit's Anki badge (NEW, QUEUED, MINED), in order; [marksJson] each hit's
     * hand mark (KNOWN, IGNORED or null); [count] how often the first hit was looked up (0: no chip).
     */
    data class Terms(
        val hitsJson: String,
        val stylesJson: String,
        val statesJson: String = "[]",
        val marksJson: String = "[]",
        val count: Int = 0,
        /** Brief definitions to show ({count, dictionary}), or null. */
        val summaryJson: String = "null",
    ) : PopupContent
    data class Kanji(val character: String, val entriesJson: String) : PopupContent

    /** The page call that shows it; the JSON is a valid JavaScript literal as it is. */
    fun script(): String = when (this) {
        is Terms -> "genkanShow($hitsJson, $stylesJson, $statesJson, $marksJson, $count, $summaryJson)"
        is Kanji -> "genkanShowKanji(${quote(character)}, $entriesJson)"
    }

    private companion object {
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** Taps inside the page: a kanji in a headword, or a dictionary's link to another word. */
interface PopupActions {
    fun lookUpKanji(character: String)
    fun lookUp(text: String)

    /** Make a card of hit [index]; the page rendered its glossary and furigana. */
    fun mine(index: Int, glossaryHtml: String, firstGlossaryHtml: String, furiganaPlain: String)

    /** Hit [index]'s known or ignore button ([status]: KNOWN or IGNORED) was pressed. */
    fun setMark(index: Int, status: String)
}

/**
 * The dictionary popup: assets/popup rendered in a WebView, since Yomitan definitions are
 * HTML-shaped structured content with their own CSS and images. Pages and images come from
 * inside the app through [WebViewAssetLoader]; nothing is fetched from the network.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryPopup(content: PopupContent?, dictionaries: Dictionaries, actions: PopupActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = remember { PopupState() }
    val webView = remember {
        val loader = WebViewAssetLoader.Builder()
            // Checked in order: media first, then the page itself (assets/popup/…; the handler
            // drops its prefix, so it sits at the root).
            .addPathHandler("/popup/media/", MediaHandler(dictionaries))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(actions, this), "GenkanPopup")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // Links never leave the popup; internal ones are handled in the page.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onPageFinished(view: WebView, url: String) {
                    state.loaded = true
                    state.pending?.let { view.evaluateJavascript(it.script(), null) }
                }
            }
            loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/popup/popup.html")
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier, update = {
        if (content != null && content != state.shown) {
            val shown = state.shown
            // Only the badges changed: update them in place, keeping the scroll position.
            val script = if (content is PopupContent.Terms && shown is PopupContent.Terms && shown.hitsJson == content.hitsJson) {
                "genkanSetStates(${content.statesJson}); genkanSetMarks(${content.marksJson})"
            } else {
                content.script()
            }
            state.pending = content
            if (state.loaded) it.evaluateJavascript(script, null)
            state.shown = content
        }
    })
}

private class PopupState {
    var loaded = false
    var pending: PopupContent? = null
    var shown: PopupContent? = null
}

/** JavaScript calls arrive on a WebView thread; hand them to the UI thread. */
private class Bridge(private val actions: PopupActions, private val view: WebView) {
    @JavascriptInterface
    fun lookUpKanji(character: String) {
        view.post { actions.lookUpKanji(character) }
    }

    @JavascriptInterface
    fun lookUp(text: String) {
        view.post { actions.lookUp(text) }
    }

    @JavascriptInterface
    fun mine(index: Int, glossaryHtml: String, firstGlossaryHtml: String, furiganaPlain: String) {
        view.post { actions.mine(index, glossaryHtml, firstGlossaryHtml, furiganaPlain) }
    }

    @JavascriptInterface
    fun setMark(index: Int, status: String) {
        view.post { actions.setMark(index, status) }
    }
}

/**
 * Serves `/popup/media/<dictionary>/<path>` from the dictionary's own media. The loader hands
 * over the path already percent-decoded.
 */
private class MediaHandler(private val dictionaries: Dictionaries) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val slash = path.indexOf('/')
        if (slash < 0) return null
        val dictionary = path.substring(0, slash)
        val file = path.substring(slash + 1)
        // Called on a WebView background thread, so waiting here doesn't block the UI.
        val bytes = runBlocking { dictionaries.media(dictionary, file) } ?: return null
        return WebResourceResponse(mimeOf(file), null, ByteArrayInputStream(bytes))
    }

    private fun mimeOf(file: String) = when (file.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "avif" -> "image/avif"
        else -> "application/octet-stream"
    }
}
