package com.perfpilot.mobile

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Started by Shizuku as a "user service": it runs as the shell user,
 * so it can run the same commands the desktop tool runs over adb.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(output) { output.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2000)
        val text = synchronized(output) { output.toString() }
        return if (finished) text else "$text\n[timed out]"
    }
}
