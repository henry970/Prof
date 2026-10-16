package com.perfpilot.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Shows a saved result using the same scoring and report as the desktop tool (assets/report.html). */
class ReportActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var file: File? = null
    private var reportName: String = "PerfPilot report"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        web = findViewById(R.id.web)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnPdf).setOnClickListener { savePdf() }

        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(Bridge(), "Android")
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val f = ReportStore.resolve(this, intent.getStringExtra(EXTRA_FILE))
        if (f == null) {
            Toast.makeText(this, R.string.report_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        file = f
        val json = f.readText()
        reportName = "PerfPilot - " + f.nameWithoutExtension
        findViewById<TextView>(R.id.reportTitle).text = f.nameWithoutExtension.substringBeforeLast('_')
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // The saved JSON is a valid JavaScript object literal.
                view.evaluateJavascript("PerfReport.render($json)", null)
            }
        }
        web.loadUrl("file:///android_asset/report.html")
    }

    private fun savePdf() {
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        pm.print(reportName, web.createPrintDocumentAdapter(reportName), attrs)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onScored(score: Int, status: String) {
            val f = file ?: return
            runCatching { ReportStore.saveScore(f, score, status) }
        }
    }

    companion object {
        const val EXTRA_FILE = "file"
    }
}
