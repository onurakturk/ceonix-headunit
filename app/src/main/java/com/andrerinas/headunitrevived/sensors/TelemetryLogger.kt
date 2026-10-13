package com.andrerinas.headunitrevived.sensors

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import com.andrerinas.headunitrevived.utils.AppLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Date
import java.util.Locale

/**
 * CSV telemetry logger for [LeanEstimator]. Writes one row per ~20 Hz output tick so a test ride can
 * be replayed and the fusion (steady-state gate, kinematic vs. geometric split, raw vs. filtered
 * yaw-rate) analysed offline.
 *
 * All file I/O runs on a dedicated [HandlerThread] — never on the caller's thread — because the
 * estimator's sensor callbacks (and therefore [log]) run on the **main thread**, where a disk hit
 * during Android Auto projection would jank the video.
 *
 * Files land in the app-private external Documents dir (no storage permission needed):
 *   /sdcard/Android/data/<pkg>/files/Documents/LeanTelemetry_yyyyMMdd_HHmmss.csv
 * Pull them with `pull_telemetry.sh` in the repo root.
 */
class TelemetryLogger(context: Context) {

    private val appContext = context.applicationContext
    private val ioThread = HandlerThread("TelemetryLogger").apply { start() }
    private val io = Handler(ioThread.looper)

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var opened = false
    @Volatile private var closed = false
    private var rowsSinceFlush = 0
    var file: File? = null
        private set

    /** Lazily create the file and write the header. Idempotent; safe to call from any thread. */
    fun open() {
        if (opened || closed) return
        opened = true
        io.post {
            try {
                val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: appContext.filesDir
                if (!dir.exists()) dir.mkdirs()
                rotateOldFiles(dir)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "LeanTelemetry_$stamp.csv")
                file = f
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                w.write("Time_ms,Speed_kmh,Raw_YawRate,Filtered_YawRate,Accel_Z,Fused_LeanAngle,Kin_Lean,Corner_s,Cornering,Active,Longitudinal_G,G_Gps,G_Conf")
                w.newLine()
                w.flush()
                writer = w
                AppLog.i("TelemetryLogger: logging to ${f.absolutePath}")
            } catch (e: Exception) {
                AppLog.w("TelemetryLogger: open failed: ${e.message}")
                writer = null
            }
        }
    }

    /** Enqueue one CSV row. Formatting happens on the I/O thread so the caller allocates nothing. */
    fun log(
        timeMs: Long, speedKmh: Float, rawYaw: Float, filtYaw: Float, accelZ: Float,
        fusedLean: Float, kinLean: Float, cornerS: Float, cornering: Int, active: Int,
        longitudinalG: Float, gpsG: Float, gConf: Float
    ) {
        if (closed) return
        io.post {
            val w = writer ?: return@post
            try {
                w.write(
                    String.format(
                        Locale.US, "%d,%.2f,%.4f,%.4f,%.3f,%.2f,%.2f,%.2f,%d,%d,%.3f,%.3f,%.2f",
                        timeMs, speedKmh, rawYaw, filtYaw, accelZ, fusedLean, kinLean, cornerS, cornering, active,
                        longitudinalG, gpsG, gConf
                    )
                )
                w.newLine()
                if (++rowsSinceFlush >= FLUSH_EVERY) { w.flush(); rowsSinceFlush = 0 }
            } catch (e: Exception) {
                AppLog.w("TelemetryLogger: write failed: ${e.message}")
            }
        }
    }

    /** Flush, close, and stop the I/O thread. Idempotent; safe from any thread. */
    fun close() {
        if (closed) return
        closed = true
        io.post {
            try { writer?.flush() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
        ioThread.quitSafely()
    }

    /** Keep only the most recent [MAX_FILES] telemetry CSVs; delete the oldest beyond that. */
    private fun rotateOldFiles(dir: File) {
        try {
            val files = dir.listFiles { f -> f.name.startsWith("LeanTelemetry_") && f.name.endsWith(".csv") }
                ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_FILES)
                .forEach { runCatching { it.delete() } }
        } catch (_: Exception) {}
    }

    companion object {
        private const val FLUSH_EVERY = 20   // ~1 s at 20 Hz
        private const val MAX_FILES = 20
    }
}
