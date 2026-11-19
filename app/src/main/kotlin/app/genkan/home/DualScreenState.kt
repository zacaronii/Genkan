package app.genkan.home

import android.content.Context
import android.view.Display
import app.genkan.display.DisplayRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ScreenMode { SINGLE, DUAL }

data class DualScreenStatus(
    val mode: ScreenMode = ScreenMode.SINGLE,
    /** Display the companion is running on, or null while it is not running. */
    val lowerDisplayId: Int? = null,
    /** The real secondary screen, whether or not the companion is on it. */
    val secondaryDisplayId: Int? = null,
    val isDefaultHome: Boolean = false,
    val homeAliasEnabled: Boolean = false,
    val companionEnabled: Boolean = true,
    /**
     * Fallback latch: false once the OS has refused to place the companion on the secondary
     * display (T2). Cleared only by [DualScreenReducer.reprobe].
     */
    val secondaryUsable: Boolean = true,
)

/** Pure state transitions, kept apart from Android so they can be unit tested. */
object DualScreenReducer {

    fun onLowerPlaced(status: DualScreenStatus, displayId: Int): DualScreenStatus =
        if (displayId == Display.DEFAULT_DISPLAY) {
            status.copy(
                mode = ScreenMode.SINGLE,
                lowerDisplayId = null,
                companionEnabled = false,
                secondaryUsable = false,
            )
        } else {
            status.copy(mode = ScreenMode.DUAL, lowerDisplayId = displayId)
        }

    fun onLowerGone(status: DualScreenStatus, displayId: Int): DualScreenStatus =
        if (status.lowerDisplayId == displayId) {
            status.copy(mode = ScreenMode.SINGLE, lowerDisplayId = null)
        } else {
            status
        }

    fun onReleased(status: DualScreenStatus): DualScreenStatus =
        status.copy(mode = ScreenMode.SINGLE, lowerDisplayId = null, companionEnabled = false)

    fun reprobe(status: DualScreenStatus): DualScreenStatus =
        status.copy(secondaryUsable = true, companionEnabled = true)

    fun withEnvironment(
        status: DualScreenStatus,
        isDefaultHome: Boolean,
        homeAliasEnabled: Boolean,
        companionEnabled: Boolean,
        secondaryDisplayId: Int?,
    ): DualScreenStatus {
        val lowerStillThere = status.lowerDisplayId != null && status.lowerDisplayId == secondaryDisplayId
        return status.copy(
            isDefaultHome = isDefaultHome,
            homeAliasEnabled = homeAliasEnabled,
            companionEnabled = companionEnabled,
            secondaryDisplayId = secondaryDisplayId,
            mode = if (lowerStillThere) ScreenMode.DUAL else ScreenMode.SINGLE,
            lowerDisplayId = status.lowerDisplayId.takeIf { lowerStillThere },
        )
    }
}

/**
 * The single source of truth for the two screens. Both activities read [status]; neither keeps
 * its own copy of "is the companion up".
 */
@Singleton
class DualScreenState @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displays: DisplayRepository,
) {
    private val _status = MutableStateFlow(DualScreenStatus())
    val status: StateFlow<DualScreenStatus> = _status.asStateFlow()

    fun refresh() = _status.update {
        DualScreenReducer.withEnvironment(
            it,
            isDefaultHome = SecondaryHomeComponent.isDefaultHome(context),
            homeAliasEnabled = HomeAppComponent.isEnabled(context),
            companionEnabled = SecondaryHomeComponent.isEnabled(context),
            secondaryDisplayId = displays.secondaryDisplayId(),
        )
    }

    fun onLowerPlaced(displayId: Int) = _status.update { DualScreenReducer.onLowerPlaced(it, displayId) }

    fun onLowerGone(displayId: Int) = _status.update { DualScreenReducer.onLowerGone(it, displayId) }

    /** Hands the bottom screen back to the system (T1). */
    fun releaseLower() {
        SecondaryHomeComponent.setEnabled(context, false)
        _status.update(DualScreenReducer::onReleased)
    }

    fun reprobe() {
        SecondaryHomeComponent.setEnabled(context, true)
        _status.update(DualScreenReducer::reprobe)
    }
}
