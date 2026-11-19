package app.genkan.stats

/**
 * A line as the reading stats see it: the parts of Furoshiki's TextLine that
 * [ActivityTracker] uses. [sourceId] identifies the text within its game
 * ([app.genkan.reader.Lines.sourceId]), so the same line shown again is recognised.
 */
data class TextLine(
    val text: String,
    val sourceId: String,
    /** A choice's answers: counted apart from narrative text. */
    val isChoice: Boolean = false,
)
