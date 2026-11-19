package app.genkan.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.genkan.romm.RomMSession
import app.genkan.romm.RomMState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: RomMSession,
) : ViewModel() {

    val state: StateFlow<RomMState> = session.state

    var server by mutableStateOf("")
    var pairingCode by mutableStateOf("")
    var usePairingCode by mutableStateOf(false)

    fun signIn() {
        if (server.isBlank()) return
        if (usePairingCode) session.loginWithPairingCode(server, pairingCode) else session.startDeviceLogin(server)
    }

    fun cancel() = session.cancelLogin()
}
