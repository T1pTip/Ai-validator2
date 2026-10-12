package com.aiorigin.detector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.Locale

data class DeviceProfile(
    val device: String,
    val physicalRamGb: Double,
    val availableRamGb: Double,
    val cpuCores: Int,
    val supportedAbis: String,
    val thermalStatus: String,
    val recommendedMode: String
)

object DeviceProfiler {
    fun inspect(context: Context): DeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "Unavailable"
        val ram = memory.totalMem.toDouble() / 1_073_741_824.0
        val available = memory.availMem.toDouble() / 1_073_741_824.0
        val mode = when {
            memory.lowMemory || available < 2.0 -> "Conservative"
            thermal in listOf("Severe", "Critical", "Emergency", "Shutdown") -> "Conservative"
            available >= 4.0 && ram >= 10.0 && thermal in listOf("Normal", "Light") -> "Performance"
            else -> "Balanced"
        }
        return DeviceProfile(
            "${Build.MANUFACTURER} ${Build.MODEL}", ram, available,
            Runtime.getRuntime().availableProcessors(), Build.SUPPORTED_ABIS.joinToString(), thermal, mode
        )
    }
}
