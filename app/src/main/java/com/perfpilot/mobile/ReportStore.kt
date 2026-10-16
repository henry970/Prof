package com.perfpilot.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved test results: one JSON file per run, plus a small ".score" file written once the report is scored. */
object ReportStore {

    data class Entry(
        val file: File,
        val appLabel: String,
        val packageName: String,
        val createdAt: Long,
        val score: Int?,
        val status: String?,
    )

    fun dir(context: Context): File = File(context.filesDir, "reports").apply { mkdirs() }

    fun save(context: Context, raw: JSONObject): File {
        val pkg = raw.getJSONObject("app").getString("package").replace(Regex("""[^\w.]"""), "_")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "${pkg}_$stamp.json")
        file.writeText(raw.toString())
        return file
    }

    /** Only files inside our own reports folder can be opened. */
    fun resolve(context: Context, path: String?): File? {
        if (path.isNullOrEmpty()) return null
        val file = File(path).canonicalFile
        val root = dir(context).canonicalFile
        return if (file.parentFile == root && file.isFile && file.name.endsWith(".json")) file else null
    }

    fun saveScore(file: File, score: Int, status: String) {
        File(file.path.removeSuffix(".json") + ".score").writeText("$score|$status")
    }

    fun delete(file: File) {
        file.delete()
        File(file.path.removeSuffix(".json") + ".score").delete()
    }

    fun list(context: Context): List<Entry> =
        (dir(context).listFiles(FileFilter { f -> f.name.endsWith(".json") }) ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                try {
                    val app = JSONObject(f.readText()).getJSONObject("app")
                    val scoreParts = File(f.path.removeSuffix(".json") + ".score")
                        .takeIf { it.exists() }?.readText()?.split("|")
                    Entry(
                        file = f,
                        appLabel = app.optString("label").ifEmpty { app.optString("package") },
                        packageName = app.optString("package"),
                        createdAt = f.lastModified(),
                        score = scoreParts?.getOrNull(0)?.toIntOrNull(),
                        status = scoreParts?.getOrNull(1),
                    )
                } catch (_: Exception) {
                    null
                }
            }
}
