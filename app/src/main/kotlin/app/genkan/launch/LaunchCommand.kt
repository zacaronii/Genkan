// Adapted from Argosy Launcher (GPL-3.0) @ 3480817: app/src/main/kotlin/com/nendo/argosy/data/emulator/LaunchCommand.kt
package app.genkan.launch

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

sealed class ResolvedExtra {
    abstract val key: String
    data class StringExtra(override val key: String, val value: String) : ResolvedExtra()
    data class UriExtra(override val key: String, val uri: String) : ResolvedExtra()
    data class BoolExtra(override val key: String, val value: Boolean) : ResolvedExtra()
}

/**
 * Everything one launch sends. URIs are kept as strings so the command can be built and checked
 * off-device; [toIntent] and [toShellArgv] turn it into the real thing.
 */
data class LaunchCommand(
    val action: String,
    val packageName: String,
    val activity: String,
    val dataUri: String? = null,
    val mimeType: String? = null,
    val extras: List<ResolvedExtra> = emptyList(),
    val flags: Int,
    /** Content URIs the emulator must be able to read. */
    val grantReadUris: List<String> = emptyList(),
    val clipDataUri: String? = null,
    val method: LaunchMethod = LaunchMethod.INTENT,
)

/**
 * Grants [LaunchCommand.packageName] read access to each URI and, by prefix, to its folder, so an
 * emulator can open the tracks a cue or m3u lists beside it.
 */
fun LaunchCommand.grantReads(context: Context) {
    for (raw in grantReadUris) {
        val uri = raw.toUri()
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri.pathSegments.dropLast(1).takeIf { it.size > 1 }?.let { folder ->
            val folderUri = uri.buildUpon().path(null).apply { folder.forEach(::appendPath) }.build()
            context.grantUriPermission(
                packageName, folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
    }
}

fun LaunchCommand.toIntent(): Intent {
    val intent = Intent(action).setComponent(ComponentName(packageName, activity))
    if (dataUri != null) {
        if (mimeType != null) intent.setDataAndType(dataUri.toUri(), mimeType) else intent.data = dataUri.toUri()
    }
    for (extra in extras) {
        when (extra) {
            is ResolvedExtra.StringExtra -> intent.putExtra(extra.key, extra.value)
            is ResolvedExtra.UriExtra -> intent.putExtra(extra.key, extra.uri.toUri())
            is ResolvedExtra.BoolExtra -> intent.putExtra(extra.key, extra.value)
        }
    }
    clipDataUri?.let { intent.clipData = ClipData.newRawUri(null, it.toUri()) }
    intent.addFlags(flags)
    if (grantReadUris.isNotEmpty()) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
}

/** Always the top screen: an external emulator's task cannot be moved after it starts (T11). */
fun topScreenOptions(): android.os.Bundle =
    ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()

private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun LaunchCommand.toShellArgv(displayId: Int = 0): Array<String> {
    val cmd = StringBuilder("/system/bin/am start")
    cmd.append(" --display ").append(displayId)
    cmd.append(" -a ").append(shellEscape(action))
    cmd.append(" -n ").append(shellEscape("$packageName/$activity"))
    if (dataUri != null) cmd.append(" -d ").append(shellEscape(dataUri))
    if (mimeType != null) cmd.append(" -t ").append(shellEscape(mimeType))
    if (flags != 0) cmd.append(" -f 0x").append(flags.toString(16))
    if (clipDataUri != null || grantReadUris.isNotEmpty()) cmd.append(" --grant-read-uri-permission")
    for (extra in extras) {
        when (extra) {
            is ResolvedExtra.StringExtra -> cmd.append(" --es ").append(shellEscape(extra.key)).append(" ").append(shellEscape(extra.value))
            is ResolvedExtra.UriExtra -> cmd.append(" --eu ").append(shellEscape(extra.key)).append(" ").append(shellEscape(extra.uri))
            is ResolvedExtra.BoolExtra -> cmd.append(" --ez ").append(shellEscape(extra.key)).append(" ").append(extra.value)
        }
    }
    return arrayOf("sh", "-c", cmd.toString())
}
