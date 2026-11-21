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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.genkan.home.LauncherPresence
import app.genkan.home.DualScreenState
import android.view.Display
import app.genkan.hardware.FocusDirectorActivity
import app.genkan.play.SessionTracker
import app.genkan.home.HomeAppComponent
import app.genkan.home.SecondaryHomeComponent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.genkan.input.HomeKeys
import app.genkan.input.PadKey
import app.genkan.ui.AnkiSettingsScreen
import app.genkan.ui.AnkiSettingsViewModel
import app.genkan.ui.AppViewModel
import app.genkan.ui.ReadingLogScreen
import app.genkan.ui.ReadingLogViewModel
import app.genkan.memory.SampleInbox
import app.genkan.ui.YomikataSettingsScreen
import app.genkan.ui.YomikataSettingsViewModel
import app.genkan.ui.WordsSettingsScreen
import app.genkan.ui.WordsSettingsViewModel
import app.genkan.ui.DictionariesScreen
import app.genkan.ui.DictionariesViewModel
import app.genkan.ui.LibraryScreen
import app.genkan.ui.LibraryViewModel
import app.genkan.ui.LoginScreen
import app.genkan.ui.LoginViewModel
import app.genkan.ui.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.genkan.ui.GenkanTheme
import app.genkan.ui.SetupAction
import app.genkan.ui.SetupScreen
import app.genkan.ui.SetupViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "HomeActivity"
private const val REFOCUS_DELAY_MS = 600L

/** Screens that are forms, driven by Compose focus rather than a ViewModel. */
private val FORMS = setOf(Screen.LOGIN, Screen.ANKI, Screen.READING_LOG, Screen.YOMIKATA, Screen.WORDS)

/** Top screen (display 0): the launcher. In Phase 0 it only hosts the setup screen. */
@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    @Inject lateinit var dualScreen: DualScreenState
    @Inject lateinit var sessionTracker: SessionTracker
    @Inject lateinit var launcher: LauncherPresence
    @Inject lateinit var sampleInbox: SampleInbox

    private val viewModel: SetupViewModel by viewModels()
    private val appViewModel: AppViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()
    private val dictionariesViewModel: DictionariesViewModel by viewModels()
    private val ankiSettingsViewModel: AnkiSettingsViewModel by viewModels()
    private val readingLogViewModel: ReadingLogViewModel by viewModels()
    private val yomikataSettingsViewModel: YomikataSettingsViewModel by viewModels()
    private val wordsSettingsViewModel: WordsSettingsViewModel by viewModels()

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
                    Screen.DICTIONARIES -> DictionariesScreen(dictionariesViewModel)
                    Screen.ANKI -> AnkiSettingsScreen(ankiSettingsViewModel)
                    Screen.READING_LOG -> ReadingLogScreen(readingLogViewModel)
                    Screen.YOMIKATA -> YomikataSettingsScreen(yomikataSettingsViewModel)
                    Screen.WORDS -> WordsSettingsScreen(wordsSettingsViewModel)
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
        // A focused view with no focus style of its own gets Android's default highlight, a grey
        // veil over the whole screen once the pad is used.
        root.defaultFocusHighlightEnabled = false
        root.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        dualScreen.refresh()
        viewModel.refresh()
        startCompanionIfMissing()
        lifecycleScope.launch {
            if (sessionTracker.onLauncherResumed()) reclaimFocusAfterGame()
        }
        // Back from a game: memory samples it saved go to the Yomikata inbox, if one is set.
        lifecycleScope.launch { sampleInbox.send() }
    }

    /**
     * Started and stopped rather than resumed and paused: FocusDirector is translucent, so the
     * launcher only pauses under it, and the bottom screen shouldn't flip on every tap.
     */
    override fun onStart() {
        super.onStart()
        launcher.set(true)
    }

    override fun onStop() {
        launcher.set(false)
        super.onStop()
    }

    /**
     * When a game closes, Android can hand input focus to the bottom screen, the other display
     * still showing something, so the controller stops driving the library. Pull it back once
     * the window settles. The wait also keeps the FocusDirector window clear of the emulator's
     * removal (the Thor's 400 ms add/remove crash, T5).
     */
    private fun reclaimFocusAfterGame() {
        window.decorView.postDelayed({
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !hasWindowFocus()) {
                Log.d(TAG, "Back from a game without focus; reclaiming it")
                FocusDirectorActivity.launchOnDisplay(this, Display.DEFAULT_DISPLAY)
            }
        }, REFOCUS_DELAY_MS)
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
        // Forms use Compose focus, whose buttons and text fields answer DPAD_CENTER, not A.
        if (pad == PadKey.A && screen in FORMS) return super.dispatchKeyEvent(asCenter(event))
        if (screen == Screen.LOGIN && pad != PadKey.START) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return pad != null || super.dispatchKeyEvent(event)
        when (screen) {
            Screen.LIBRARY -> {
                if (pad == PadKey.START) appViewModel.openSettings()
                else if (pad == null || !libraryViewModel.onKey(pad, event.repeatCount)) return super.dispatchKeyEvent(event)
            }
            // B steps from a group's rows back to the group list, and only then out of settings.
            Screen.SETTINGS -> {
                val result = pad?.takeIf { it != PadKey.START }?.let(viewModel::onKey)
                when {
                    pad == PadKey.START -> appViewModel.closeSettings()
                    result?.consumed == true -> result.action?.let(::run)
                    pad == PadKey.B -> appViewModel.closeSettings()
                    else -> return super.dispatchKeyEvent(event)
                }
            }
            // A form: the D-pad moves Compose focus between its fields.
            Screen.ANKI, Screen.READING_LOG, Screen.YOMIKATA, Screen.WORDS -> when (pad) {
                // B closes the keyboard first, and only then the page.
                PadKey.B -> if (!hideKeyboard()) appViewModel.closePage()
                PadKey.START -> appViewModel.closeSettings()
                else -> return super.dispatchKeyEvent(event)
            }
            Screen.DICTIONARIES -> when {
                pad == PadKey.B -> appViewModel.closePage()
                pad == PadKey.START -> appViewModel.closeSettings()
                pad == null || !dictionariesViewModel.onKey(pad) -> return super.dispatchKeyEvent(event)
            }
            Screen.LOGIN -> appViewModel.openSettings()
            Screen.LOADING -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** True when the on-screen keyboard was up and is now hidden. */
    private fun hideKeyboard(): Boolean {
        val root = window.decorView
        val visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        return visible
    }

    private fun asCenter(event: KeyEvent) = KeyEvent(
        event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
        event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
    )

    private fun run(action: SetupAction) {
        when (action) {
            SetupAction.ENABLE_HOME -> enableHome()
            SetupAction.DISABLE_HOME -> disableHome()
            SetupAction.SYNC_LIBRARY -> lifecycleScope.launch { viewModel.librarySync.sync() }
            SetupAction.DICTIONARIES -> appViewModel.openPage(Screen.DICTIONARIES)
            SetupAction.ANKI -> appViewModel.openPage(Screen.ANKI)
            SetupAction.READING_LOG -> appViewModel.openPage(Screen.READING_LOG)
            SetupAction.YOMIKATA -> appViewModel.openPage(Screen.YOMIKATA)
            SetupAction.WORDS -> appViewModel.openPage(Screen.WORDS)
            SetupAction.USAGE_ACCESS -> startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())
            )
            SetupAction.SHIZUKU -> viewModel.privileged.requestPermission()
            SetupAction.PSP_DEBUGGER -> lifecycleScope.launch {
                viewModel.ppssppText.setEnabled(!viewModel.ppssppText.enabled.first())
            }
            SetupAction.AZAHAR_RPC -> lifecycleScope.launch {
                viewModel.azaharText.setEnabled(!viewModel.azaharText.enabled.first())
            }
            SetupAction.BUILT_IN_PLAYER -> lifecycleScope.launch {
                viewModel.playerSettings.setEnabled(!viewModel.playerSettings.isEnabled())
            }
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
