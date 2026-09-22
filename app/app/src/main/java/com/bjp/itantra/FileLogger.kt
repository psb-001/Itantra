package com.bjp.itantra

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the app's event log to a file on the device, so it can be pulled into the project folder
 * and read on a laptop.
 *
 * WHY THIS EXISTS. The on-screen log panel is capped at 14 lines and dies with the app. When two
 * phones misbehave, what we need is a complete, timestamped record from both of them — and we need
 * it after the fact, not while staring at a 4-inch screen.
 *
 * WHERE IT LANDS.
 *   /sdcard/Android/data/com.bjp.itantra/files/logs/itantra.log
 *
 * Pull it with:
 *   ./pull_logs.sh              (from output/option_a_stt/android/)
 *
 * The file is capped at [MAX_BYTES]; when it grows past that it is rotated to `itantra.log.1` and
 * a fresh file is started. Two files maximum, so this can never fill the phone.
 */
object FileLogger {

    private const val TAG = "iTantra/FileLog"
    private const val DIR = "logs"
    private const val NAME = "itantra.log"
    private const val MAX_BYTES = 512L * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var lock = Any()

    /** Call once from the Activity. Safe to call again; only the first call does work. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            try {
                val dir = File(context.getExternalFilesDir(null), DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "could not create ${dir.absolutePath}")
                    return
                }
                file = File(dir, NAME)
                write("===== iTantra log opened =====")
                Log.i(TAG, "logging to ${file?.absolutePath}")
            } catch (e: Throwable) {
                Log.w(TAG, "init failed: ${e.message}")
            }
        }
    }

    /** Absolute path of the active log, or null if logging never initialised. */
    fun path(): String? = file?.absolutePath

    /** Append one line. Never throws — logging must not be able to break the app. */
    fun write(line: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("${stamp.format(Date())}  $line\n")
            } catch (e: Throwable) {
                // Deliberately swallowed. A failed log write is not worth crashing for.
            }
        }
    }

    private fun rotate(f: File) {
        val old = File(f.parentFile, "$NAME.1")
        if (old.exists()) old.delete()
        f.renameTo(old)
        f.createNewFile()
        f.appendText("${stamp.format(Date())}  ----- log rotated, previous is $NAME.1 -----\n")
    }

    /** Wipe the log. Exposed for a future "clear" button. */
    fun clear() {
        val f = file ?: return
        synchronized(lock) {
            runCatching { f.delete() }
            runCatching { f.createNewFile() }
        }
    }
}
