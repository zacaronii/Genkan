package app.genkan

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.genkan.home.DualScreenState
import app.genkan.home.HomeAppComponent
import app.genkan.home.SecondaryHomeComponent
import app.genkan.input.HomeKeys
import app.genkan.ui.GenkanTheme
import app.genkan.ui.SetupAction
import app.genkan.ui.SetupScreen
import app.genkan.ui.SetupViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "HomeActivity"

/** Top screen (display 0): the launcher. In Phase 0 it only hosts the setup screen. */
@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    @Inject lateinit var dualScreen: DualScreenState

    private val viewModel: SetupViewModel by viewModels()

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Log.d(TAG, "ROLE_HOME request returned ${it.resultCode}")
        dualScreen.refresh()
        startCompanionIfMissing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GenkanTheme {
                SetupScreen(viewModel, onAction = ::run)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dualScreen.refresh()
        startCompanionIfMissing()
    }

    /**
     * Android starts SECONDARY_HOME by itself only when it next sends Home to that display
     * (boot, display added), so a stock secondary launcher already running keeps the screen
     * after Genkan becomes home. Start the companion there directly. If the OS puts it on the
     * default display instead, LowerActivity latches single-screen and this stops retrying.
     */
    private fun startCompanionIfMissing() {
        val status = dualScreen.status.value
        val target = status.secondaryDisplayId ?: return
        if (!status.isDefaultHome || !status.companionEnabled || !status.secondaryUsable) return
        if (status.lowerDisplayId != null) return
        Log.d(TAG, "Starting companion on display $target")
        val intent = Intent(this, LowerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
    }

    /**
     * The only key sink on this screen. Navigation never goes through Compose focus; the
     * ViewModel owns the focus index.
     *
     * RestrictedApi is a lint false positive: the restriction sits on androidx.core's
     * KeyEventDispatcher.Component, not on the public Activity.dispatchKeyEvent.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (HomeKeys.isSystemHome(event)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val result = viewModel.onKey(event.keyCode)
            result.action?.let(::run)
            if (result.consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun run(action: SetupAction) {
        when (action) {
            SetupAction.ENABLE_HOME -> enableHome()
            SetupAction.DISABLE_HOME -> disableHome()
        }
    }

    private fun enableHome() {
        HomeAppComponent.setEnabled(this, true)
        dualScreen.reprobe()
        val roles = getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        } else {
            dualScreen.refresh()
            startCompanionIfMissing()
        }
    }

    private fun disableHome() {
        SecondaryHomeComponent.setEnabled(this, false)
        HomeAppComponent.setEnabled(this, false)
        dualScreen.refresh()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
