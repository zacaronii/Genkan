package app.genkan.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import app.genkan.home.DualScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

enum class SetupAction(val label: String) {
    ENABLE_HOME("Enable Genkan as Home"),
    DISABLE_HOME("Disable Genkan as Home"),
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    val dualScreen: DualScreenState,
) : ViewModel() {

    val actions: List<SetupAction> = SetupAction.entries

    private val _focusIndex = MutableStateFlow(0)
    val focusIndex: StateFlow<Int> = _focusIndex.asStateFlow()

    /**
     * Gamepad navigation. Returns the action to run when the confirm button is pressed, null
     * otherwise; consumed tells the activity whether to swallow the key.
     */
    fun onKey(keyCode: Int): KeyResult = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> move(-1)
        KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            KeyResult(consumed = true, action = actions[_focusIndex.value])
        else -> KeyResult(consumed = false)
    }

    fun focus(action: SetupAction) {
        _focusIndex.value = actions.indexOf(action)
    }

    private fun move(delta: Int): KeyResult {
        _focusIndex.update { (it + delta).coerceIn(0, actions.lastIndex) }
        return KeyResult(consumed = true)
    }

    data class KeyResult(val consumed: Boolean, val action: SetupAction? = null)
}
