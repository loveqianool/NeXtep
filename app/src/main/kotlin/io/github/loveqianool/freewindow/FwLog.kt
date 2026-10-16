package io.github.loveqianool.freewindow

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dual logging: logcat + file.
 * File: /data/local/tmp/freewindow.log (world-readable, no permission needed).
 * Use [i]/[w] instead of Log.i/w directly for important diagnostics.
 */
object FwLog {
    const val TAG = "FreeWindow"
    private val file = File("/data/local/tmp/freewindow.log")
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun i(msg: String) {
        Log.i(TAG, msg)
        write("I", msg)
    }

    fun w(msg: String, e: Throwable? = null) {
        if (e != null) Log.w(TAG, msg, e) else Log.w(TAG, msg)
        write("W", if (e != null) "$msg | ${e.javaClass.simpleName}: ${e.message}" else msg)
    }

    private fun write(level: String, msg: String) {
        try {
            synchronized(lock) {
                // Rotate if > 512KB.
                if (file.exists() && file.length() > 512 * 1024) {
                    file.delete()
                }
                FileWriter(file, true).use { w ->
                    w.append(dateFormat.format(Date()))
                        .append(' ').append(level)
                        .append(' ').append(msg)
                        .append('\n')
                }
                // Make readable via `cat` without root (tmp is world-accessible).
                file.setReadable(true, false)
            }
        } catch (e: Throwable) {
            // Never crash for logging.
        }
    }

    /** Clears the log file. */
    fun clear() {
        try {
            synchronized(lock) { file.delete() }
        } catch (e: Throwable) { /* ignore */ }
    }
}
