// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/util/HomeAppComponent.kt
package app.genkan.home

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The HOME activity-alias. It ships disabled so installing Genkan never takes over Home; the
 * setup screen enables it right before asking for ROLE_HOME.
 */
object HomeAppComponent {

    const val ALIAS_CLASS = "app.genkan.HomeAlias"

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context.packageName, ALIAS_CLASS)
        if (context.packageManager.getComponentEnabledSetting(component) == state) return
        context.packageManager.setComponentEnabledSetting(
            component, state, PackageManager.DONT_KILL_APP
        )
    }

    fun isEnabled(context: Context): Boolean =
        isEnabledState(
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS_CLASS)
            ),
            manifestDefault = false
        )
}

/** Resolves a component's enabled setting, where DEFAULT means whatever the manifest says. */
internal fun isEnabledState(setting: Int, manifestDefault: Boolean): Boolean = when (setting) {
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestDefault
    else -> false
}
