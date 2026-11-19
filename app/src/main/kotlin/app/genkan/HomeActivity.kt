package app.genkan

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Environment
import app.genkan.ui.LibraryEvent
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.genkan.home.DualScreenState
import app.genkan.home.HomeAppComponent
import app.genkan.home.SecondaryHomeComponent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.genkan.input.HomeKeys
import app.genkan.input.PadKey
import app.genkan.ui.AppViewModel
import app.genkan.ui.LibraryScreen
import app.genkan.ui.LibraryViewModel
import app.genkan.ui.LoginScreen
import app.genkan.ui.LoginViewModel
import app.genkan.ui.Screen
import kotlinx.coroutines.launch
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
    private val appViewModel: AppViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Log.d(TAG, "ROLE_HOME request returned ${it.resultCode}")
        dualScreen.refresh()
        startCompanionIfMissing()
        libraryViewModel.refreshLocal()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var askedForNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryViewModel.hasStorageAccess = { Environment.isExternalStorageManager() }
        lifecycleScope.launch {
            libraryViewModel.events.collect { event ->
                when (event) {
                    LibraryEvent.NeedStorageAccess -> startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())
                    )
                    LibraryEvent.NeedNotifications -> if (!askedForNotifications &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askedForNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
        setContent {
            GenkanTheme {
                val screen by appViewModel.screen.collectAsStateWithLifecycle()
                when (screen) {
                    Screen.LOADING -> Unit
                    Screen.LOGIN -> LoginScreen(loginViewModel)
                    Screen.LIBRARY -> LibraryScreen(libraryViewModel)
                    Screen.SETTINGS -> SetupScreen(viewModel, onAction = ::run)
                }
            }
        }
        holdFocusInTouchMode()
    }

    /**
     * When a touch is followed by a D-pad press, ViewRootImpl leaves touch mode by moving focus to
     * a focusable view and swallows that press before dispatchKeyEvent sees it. Keeping the root
     * Compose view focused in touch mode leaves nothing to move, so the first press after a tap
     * reaches the ViewModel like any other.
     */
    private fun holdFocusInTouchMode() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) ?: return
        root.isFocusableInTouchMode = true
        root.requestFocus()
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
     * The only key sink on this screen. The library and settings never use Compose focus; their
     * ViewModels own the focus index. The login form is the exception and keeps Compose focus,
     * so its keys fall through to the view hierarchy.
     *
     * RestrictedApi is a lint false positive: the restriction sits on androidx.core's
     * KeyEventDispatcher.Component, not on the public Activity.dispatchKeyEvent.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (HomeKeys.isSystemHome(event)) return super.dispatchKeyEvent(event)
        val pad = PadKey.from(event.keyCode)
        val screen = appViewModel.screen.value
        if (screen == Screen.LOGIN && pad != PadKey.START) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return pad != null || super.dispatchKeyEvent(event)
        when (screen) {
            Screen.LIBRARY -> {
                if (pad == PadKey.START) appViewModel.openSettings()
                else if (pad == null || !libraryViewModel.onKey(pad)) return super.dispatchKeyEvent(event)
            }
            Screen.SETTINGS -> {
                if (pad == PadKey.B || pad == PadKey.START) {
                    appViewModel.closeSettings()
                } else {
                    val result = viewModel.onKey(event.keyCode)
                    result.action?.let(::run)
                    if (!result.consumed) return super.dispatchKeyEvent(event)
                }
            }
            Screen.LOGIN -> appViewModel.openSettings()
            Screen.LOADING -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun run(action: SetupAction) {
        when (action) {
            SetupAction.ENABLE_HOME -> enableHome()
            SetupAction.DISABLE_HOME -> disableHome()
            SetupAction.SYNC_LIBRARY -> lifecycleScope.launch { viewModel.librarySync.sync() }
            SetupAction.SIGN_OUT -> {
                viewModel.romm.signOut()
                appViewModel.closeSettings()
            }
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
