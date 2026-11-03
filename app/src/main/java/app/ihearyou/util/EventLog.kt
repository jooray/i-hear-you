package app.ihearyou.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Append-only event log in the app's files dir (events.log, rotated to events.1.log at 512 KB).
 * Survives the process being killed, unlike logcat or the in-memory log, so a press, the command
 * sent and what the player did afterwards can be read back later:
 * `adb shell run-as app.ihearyou cat files/events.1.log files/events.log`
 */
object EventLog {

    private const val MAX_BYTES = 512 * 1024
    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            dir = context.applicationContext.filesDir
            append("---- process start pid=${android.os.Process.myPid()} ${lastExit(context)}")
        }
    }

    fun append(line: String) {
        val d = dir ?: return
        val stamped = LocalDateTime.now().format(stamp) + "  " + line + "\n"
        writer.execute {
            runCatching {
                val file = File(d, "events.log")
                if (file.length() > MAX_BYTES) file.renameTo(File(d, "events.1.log"))
                file.appendText(stamped)
            }
        }
    }

    /** Why the previous process ended: tells our own kill apart from a crash or an update. */
    private fun lastExit(context: Context): String = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        val e = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            ?: return "previous exit: none recorded"
        val reason = when (e.reason) {
            ApplicationExitInfo.REASON_SIGNALED -> "signaled ${e.status}"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            else -> "reason ${e.reason}"
        }
        val at = java.time.Instant.ofEpochMilli(e.timestamp).atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime().format(stamp)
        "previous exit: $at pid=${e.pid} $reason importance=${e.importance} ${e.description ?: ""}".trim()
    }.getOrDefault("previous exit: unknown")
}
