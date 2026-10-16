package com.perfpilot.mobile

import org.json.JSONObject

/** Parsers for shell output. Same rules as the desktop CLI, so results match. */
object Parsers {

    private fun intOf(text: String, pattern: String, option: RegexOption? = null): Int? {
        val re = if (option != null) Regex(pattern, option) else Regex(pattern)
        return re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** `am start -S -W` output. */
    fun amStart(text: String): JSONObject? {
        val total = intOf(text, """TotalTime:\s*(\d+)""") ?: return null
        return JSONObject().apply {
            put("totalMs", total)
            put("waitMs", intOf(text, """WaitTime:\s*(\d+)""") ?: JSONObject.NULL)
            put("launchState", Regex("""LaunchState:\s*(\w+)""").find(text)?.groupValues?.get(1) ?: JSONObject.NULL)
        }
    }

    /** `dumpsys gfxinfo <pkg>` output. */
    fun gfxinfo(text: String): JSONObject {
        val jankPct = Regex("""Janky frames:\s*\d+\s*\(([\d.]+)%\)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        fun put(o: JSONObject, key: String, v: Number?) = o.put(key, v ?: JSONObject.NULL)
        return JSONObject().also { o ->
            put(o, "totalFrames", intOf(text, """Total frames rendered:\s*(\d+)"""))
            put(o, "jankyFrames", intOf(text, """Janky frames:\s*(\d+)"""))
            put(o, "jankPct", jankPct)
            put(o, "p50", intOf(text, """50th percentile:\s*(\d+)ms"""))
            put(o, "p90", intOf(text, """90th percentile:\s*(\d+)ms"""))
            put(o, "p95", intOf(text, """95th percentile:\s*(\d+)ms"""))
            put(o, "p99", intOf(text, """99th percentile:\s*(\d+)ms"""))
            put(o, "missedVsync", intOf(text, """Number Missed Vsync:\s*(\d+)"""))
            put(o, "slowUiThread", intOf(text, """Number Slow UI thread:\s*(\d+)"""))
            put(o, "deadlineMissed", intOf(text, """Number Frame deadline missed:\s*(\d+)"""))
        }
    }

    /** Total PSS in MB from `dumpsys meminfo <pkg>`. */
    fun meminfoMB(text: String): Double? {
        val kb = intOf(text, """TOTAL PSS:\s*(\d+)""")
            ?: intOf(text, """^\s*TOTAL\s+(\d+)""", RegexOption.MULTILINE)
            ?: return null
        return Math.round(kb / 1024.0 * 10) / 10.0
    }

    /** utime + stime (clock ticks) from /proc/<pid>/stat. */
    fun procStatTicks(text: String): Long? {
        val close = text.lastIndexOf(')')
        if (close < 0 || close + 2 > text.length) return null
        val fields = text.substring(close + 2).trim().split(Regex("""\s+"""))
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** Last line of `cmd package resolve-activity --brief <pkg>`. */
    fun resolveActivity(text: String): String? {
        val last = text.trim().lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        return if (Regex("""^[\w.]+/[\w.$]+$""").matches(last)) last else null
    }

    fun versionName(dumpsysPackage: String): String? =
        Regex("""versionName=(\S+)""").find(dumpsysPackage)?.groupValues?.get(1)

    fun versionCode(dumpsysPackage: String): String? =
        Regex("""versionCode=(\d+)""").find(dumpsysPackage)?.groupValues?.get(1)
}
