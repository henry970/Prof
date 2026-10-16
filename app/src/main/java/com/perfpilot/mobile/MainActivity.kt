package com.perfpilot.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    data class AppItem(val label: String, val packageName: String, val icon: Drawable?)

    private var selected: AppItem? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnAccess: Button
    private lateinit var appIcon: ImageView
    private lateinit var appName: TextView
    private lateinit var appPackage: TextView
    private lateinit var inputRuns: EditText
    private lateinit var radioMonkey: RadioButton
    private lateinit var radioManual: RadioButton
    private lateinit var inputEvents: EditText
    private lateinit var inputSeconds: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var progressCard: View
    private lateinit var progressText: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var historyEmpty: TextView

    private val stateListener: () -> Unit = { renderState() }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { renderAccess() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { renderAccess() } }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { renderAccess() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnAccess = findViewById(R.id.btnAccess)
        appIcon = findViewById(R.id.appIcon)
        appName = findViewById(R.id.appName)
        appPackage = findViewById(R.id.appPackage)
        inputRuns = findViewById(R.id.inputRuns)
        radioMonkey = findViewById(R.id.radioMonkey)
        radioManual = findViewById(R.id.radioManual)
        inputEvents = findViewById(R.id.inputEvents)
        inputSeconds = findViewById(R.id.inputSeconds)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        progressCard = findViewById(R.id.progressCard)
        progressText = findViewById(R.id.progressText)
        historyList = findViewById(R.id.historyList)
        historyEmpty = findViewById(R.id.historyEmpty)

        findViewById<View>(R.id.appRow).setOnClickListener { pickApp() }
        btnAccess.setOnClickListener { onAccessClicked() }
        btnStart.setOnClickListener { startTest() }
        btnStop.setOnClickListener { TestService.stop(this) }
        radioMonkey.setOnCheckedChangeListener { _, _ -> renderMode() }
        radioManual.setOnCheckedChangeListener { _, _ -> renderMode() }

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        TestState.addListener(stateListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        renderMode()
    }

    override fun onResume() {
        super.onResume()
        renderAccess()
        renderState()
        renderHistory()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        TestState.removeListener(stateListener)
        super.onDestroy()
    }

    // ---------- Shizuku access ----------

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(Shell.SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun renderAccess() {
        val status = Shell.status()
        val (color, text, button) = when (status) {
            Shell.Status.READY -> Triple(R.color.good, getString(R.string.access_ready), null)
            Shell.Status.NO_PERMISSION -> Triple(R.color.warn, getString(R.string.access_no_permission), getString(R.string.grant_access))
            Shell.Status.OUTDATED -> Triple(R.color.poor, getString(R.string.access_outdated), getString(R.string.open_shizuku))
            Shell.Status.NOT_RUNNING ->
                if (shizukuInstalled()) Triple(R.color.warn, getString(R.string.access_not_running), getString(R.string.open_shizuku))
                else Triple(R.color.poor, getString(R.string.access_not_installed), getString(R.string.get_shizuku))
        }
        statusDot.background.mutate().setTint(ContextCompat.getColor(this, color))
        statusText.text = text
        btnAccess.visibility = if (button == null) View.GONE else View.VISIBLE
        if (button != null) btnAccess.text = button
        renderState()
    }

    private fun onAccessClicked() {
        when (Shell.status()) {
            Shell.Status.NO_PERMISSION -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    Toast.makeText(this, R.string.permission_denied_before, Toast.LENGTH_LONG).show()
                    openShizuku()
                } else {
                    Shizuku.requestPermission(REQ_SHIZUKU)
                }
            }
            Shell.Status.READY -> Unit
            else -> if (shizukuInstalled()) openShizuku() else openUrl(SHIZUKU_STORE)
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)
        if (launch != null) startActivity(launch) else openUrl(SHIZUKU_STORE)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- choosing the app ----------

    private fun pickApp() {
        Toast.makeText(this, R.string.loading_apps, Toast.LENGTH_SHORT).show()
        thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != packageName }
                .map { (pkg, info) -> AppItem(info.loadLabel(pm).toString(), pkg, runCatching { info.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { if (!isFinishing) showAppDialog(apps) }
        }
    }

    private fun showAppDialog(all: List<AppItem>) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_apps, null)
        val search = content.findViewById<EditText>(R.id.search)
        val list = content.findViewById<ListView>(R.id.list)
        val shown = ArrayList(all)
        val adapter = object : ArrayAdapter<AppItem>(this, R.layout.item_app, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
                val item = getItem(position)
                if (item != null) {
                    v.findViewById<ImageView>(R.id.icon).setImageDrawable(item.icon)
                    v.findViewById<TextView>(R.id.label).text = item.label
                    v.findViewById<TextView>(R.id.pkg).text = item.packageName
                }
                return v
            }
        }
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                adapter.clear()
                adapter.addAll(all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) })
            }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_app)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { setSelected(it) }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun setSelected(item: AppItem) {
        selected = item
        appIcon.setImageDrawable(item.icon)
        appName.text = item.label
        appPackage.text = item.packageName
        renderState()
    }

    // ---------- running ----------

    private fun renderMode() {
        inputEvents.isEnabled = radioMonkey.isChecked
        inputSeconds.isEnabled = radioManual.isChecked
    }

    private fun numberOf(e: EditText, def: Int, min: Int, max: Int): Int =
        (e.text.toString().trim().toIntOrNull() ?: def).coerceIn(min, max)

    private fun startTest() {
        val app = selected
        if (app == null) {
            Toast.makeText(this, R.string.choose_app_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Shell.status() != Shell.Status.READY) {
            Toast.makeText(this, R.string.need_access, Toast.LENGTH_LONG).show()
            return
        }
        val cfg = TestConfig(
            packageName = app.packageName,
            appLabel = app.label,
            iterations = numberOf(inputRuns, 5, 1, 20),
            warmup = 1,
            mode = if (radioManual.isChecked) "manual" else "monkey",
            monkeyEvents = numberOf(inputEvents, 400, 50, 5000),
            manualSeconds = numberOf(inputSeconds, 20, 5, 300),
        )
        TestService.start(this, cfg)
        if (cfg.mode == "manual") Toast.makeText(this, R.string.manual_hint, Toast.LENGTH_LONG).show()
    }

    private fun renderState() {
        val running = TestState.running
        btnStart.isEnabled = !running && selected != null && Shell.status() == Shell.Status.READY
        btnStart.visibility = if (running) View.GONE else View.VISIBLE
        btnStop.visibility = if (running) View.VISIBLE else View.GONE
        val log = TestState.log()
        progressCard.visibility = if (log.isEmpty()) View.GONE else View.VISIBLE
        progressText.text = log.takeLast(10).joinToString("\n")
        if (!running && TestState.lastReportPath != null) renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val entries = ReportStore.list(this)
        historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (e in entries) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_report, historyList, false)
            row.findViewById<TextView>(R.id.reportApp).text = e.appLabel
            row.findViewById<TextView>(R.id.reportDate).text = fmt.format(Date(e.createdAt))
            val scoreView = row.findViewById<TextView>(R.id.reportScore)
            scoreView.text = e.score?.toString() ?: "–"
            val color = when (e.status) {
                "good" -> R.color.good
                "needs-work" -> R.color.warn
                "poor" -> R.color.poor
                else -> R.color.muted
            }
            scoreView.background.mutate().setTint(ContextCompat.getColor(this, color))
            row.setOnClickListener {
                startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_FILE, e.file.absolutePath))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_report, e.appLabel))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        ReportStore.delete(e.file)
                        renderHistory()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            historyList.addView(row)
        }
    }

    companion object {
        private const val REQ_SHIZUKU = 42
        private const val REQ_NOTIFY = 43
        private const val SHIZUKU_STORE = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    }
}
