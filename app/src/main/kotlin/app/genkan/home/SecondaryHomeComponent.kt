// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/util/SecondaryHomeComponent.kt
package app.genkan.home

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.genkan.LowerActivity

/**
 * Enable/disable the SECONDARY_HOME activity component. Disabling is the only way to
 * release the bottom screen back to the OEM secondary home; finish() alone gets the
 * activity respawned by the OS (T1).
 */
object SecondaryHomeComponent {

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context, LowerActivity::class.java)
        if (context.packageManager.getComponentEnabledSetting(component) == state) return
        context.packageManager.setComponentEnabledSetting(
            component, state, PackageManager.DONT_KILL_APP
        )
    }

    fun isEnabled(context: Context): Boolean =
        isEnabledState(
            context.packageManager.getComponentEnabledSetting(
                ComponentName(context, LowerActivity::class.java)
            ),
            manifestDefault = true
        )

    fun isDefaultHome(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java)
        return roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
            roles.isRoleHeld(RoleManager.ROLE_HOME)
    }
}
