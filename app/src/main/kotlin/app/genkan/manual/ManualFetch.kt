package app.genkan.manual

/**
 * A manual being fetched, for the control that asked for it to show: under way ([Busy], with the
 * share done once the server has said how long the file is), or failed ([Failed]).
 */
sealed interface ManualFetch {
    data class Busy(val progress: Float? = null) : ManualFetch
    data class Failed(val message: String) : ManualFetch
}
