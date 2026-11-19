package app.genkan.ui

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.genkan.dictionary.Dictionaries
import app.genkan.dictionary.DictionaryEntity
import app.genkan.input.PadKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dictionaries screen: row 0 imports from Download, the rest are the dictionaries in popup
 * order. A switches one on or off, L1/R1 move it, X twice removes it.
 */
@HiltViewModel
class DictionariesViewModel @Inject constructor(val dictionaries: Dictionaries) : ViewModel() {

    val list: StateFlow<List<DictionaryEntity>> =
        dictionaries.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _focusIndex = MutableStateFlow(0)
    val focusIndex: StateFlow<Int> = _focusIndex.asStateFlow()

    private val _armedRemoval = MutableStateFlow<Long?>(null)
    /** The dictionary whose removal X has asked to confirm. */
    val armedRemoval: StateFlow<Long?> = _armedRemoval.asStateFlow()

    private val focused: DictionaryEntity? get() = list.value.getOrNull(_focusIndex.value - 1)

    /** True when the key was used. */
    fun onKey(key: PadKey): Boolean {
        when (key) {
            PadKey.UP -> move(-1)
            PadKey.DOWN -> move(1)
            PadKey.A -> activate(_focusIndex.value)
            PadKey.L1 -> reorder(-1)
            PadKey.R1 -> reorder(1)
            PadKey.X -> remove()
            else -> return false
        }
        return true
    }

    /** Touch: a tap focuses a row and acts like A. */
    fun tap(index: Int) {
        _focusIndex.value = index
        activate(index)
    }

    fun importFromDownload() {
        viewModelScope.launch {
            dictionaries.importFrom(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }
    }

    fun reorderAt(index: Int, delta: Int) {
        _focusIndex.value = index
        reorder(delta)
    }

    private fun activate(index: Int) {
        _armedRemoval.value = null
        if (index == 0) {
            importFromDownload()
            return
        }
        val dictionary = list.value.getOrNull(index - 1) ?: return
        viewModelScope.launch { dictionaries.setEnabled(dictionary, !dictionary.enabled) }
    }

    private fun move(delta: Int) {
        _armedRemoval.value = null
        _focusIndex.update { (it + delta).coerceIn(0, list.value.size) }
    }

    private fun reorder(delta: Int) {
        val dictionary = focused ?: return
        _armedRemoval.value = null
        val target = (_focusIndex.value + delta).coerceIn(1, list.value.size)
        _focusIndex.value = target
        viewModelScope.launch { dictionaries.move(dictionary, delta) }
    }

    private fun remove() {
        val dictionary = focused ?: return
        if (_armedRemoval.value != dictionary.id) {
            _armedRemoval.value = dictionary.id
            return
        }
        _armedRemoval.value = null
        viewModelScope.launch { dictionaries.remove(dictionary) }
        _focusIndex.update { it.coerceAtMost(list.value.size - 1) }
    }
}
