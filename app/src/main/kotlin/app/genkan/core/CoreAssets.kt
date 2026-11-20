package app.genkan.core

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks a core's bundled files into its system folder, once per APK build. */
object CoreAssets {
    private const val TAG = "CoreAssets"
    private const val MARKER = ".genkan-unpacked"

    fun unpack(context: Context, zip: String, into: File) {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val marker = File(into, MARKER)
        if (marker.isFile && marker.readText() == stamp) return
        into.deleteRecursively()
        into.mkdirs()
        val root = into.canonicalPath + File.separator
        ZipInputStream(context.assets.open("cores/$zip")).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(into, entry.name)
                // Nothing may land outside the folder.
                if (!target.canonicalPath.startsWith(root)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        marker.writeText(stamp)
        Log.i(TAG, "Unpacked $zip into $into")
    }
}
