package app.genkan.core

import java.io.File

/** The CPU's and GPU's clocks, load and heat as the kernel shows them, for the player's stats; null where it doesn't. */
object DeviceHeat {
    /** The fastest core's current clock, in MHz. */
    fun fastestMhz(): Int? = File("/sys/devices/system/cpu").listFiles { f -> CPU.matches(f.name) }
        ?.mapNotNull { File(it, "cpufreq/scaling_cur_freq").readNumber() }
        ?.maxOrNull()?.let { (it / 1000).toInt() }

    /** The hottest CPU thermal zone, in °C. */
    fun hottestCpuC(): Int? = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        ?.filter { File(it, "type").readText().trim().startsWith("cpu") }
        ?.mapNotNull { File(it, "temp").readNumber() }
        ?.maxOrNull()?.let { (it / 1000).toInt() }

    /** How busy the GPU is, in percent (Adreno's kgsl). */
    fun gpuBusyPercent(): Int? = runCatching { File(KGSL, "gpu_busy_percentage").readText().trim().removeSuffix("%").trim().toInt() }.getOrNull()

    /** The GPU's current clock, in MHz. */
    fun gpuMhz(): Int? = File(KGSL, "devfreq/cur_freq").readNumber()?.let { (it / 1_000_000).toInt() }

    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"

    private fun File.readNumber(): Long? = runCatching { readText().trim().toLong() }.getOrNull()

    private val CPU = Regex("cpu\\d+")
}
