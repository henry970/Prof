package com.perfpilot.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Shared progress of the running test, observed by the main screen. */
object TestState {
    @Volatile
    var running = false
        private set

    @Volatile
    var lastReportPath: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun log(): List<String> = synchronized(lines) { lines.toList() }

    fun start() {
        synchronized(lines) { lines.clear() }
        running = true
        lastReportPath = null
        lastError = null
        changed()
    }

    fun post(message: String) {
        synchronized(lines) {
            lines.add(message)
            if (lines.size > 200) lines.removeAt(0)
        }
        changed()
    }

    fun finish(reportPath: String?, error: String?) {
        running = false
        lastReportPath = reportPath
        lastError = error
        if (error != null) post("Stopped: $error") else post("Done. Opening the report…")
    }

    private fun changed() {
        main.post { listeners.forEach { it() } }
    }
}
