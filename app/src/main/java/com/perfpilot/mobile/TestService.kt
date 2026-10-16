package com.perfpilot.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the test in the foreground, because the app being tested is on screen the whole time
 * and Android would otherwise stop our work in the background.
 */
class TestService : Service() {

    private var runner: TestRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TestState.post("Stopping…")
            val r = runner
            if (r != null) thread { r.cancel() } else stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) return START_NOT_STICKY
        // Started with startForegroundService, so we must go foreground right away, even if a test is already running.
        startInForeground(buildNotification(getString(R.string.notif_starting), running = true))
        if (TestState.running) return START_NOT_STICKY

        val cfg = TestConfig.fromIntent(intent)
        TestState.start()

        thread(name = "perfpilot-test") {
            var report: File? = null
            var error: String? = null
            try {
                TestState.post("Connecting to Shizuku…")
                if (!Shell.connect()) {
                    throw IllegalStateException("Couldn't start the Shizuku shell. Open Shizuku, check it's running, then try again.")
                }
                val r = TestRunner(this, cfg) { msg ->
                    TestState.post(msg)
                    notify(buildNotification(msg, running = true))
                }
                runner = r
                val raw = r.run()
                report = ReportStore.save(this, raw)
            } catch (e: CancelledException) {
                error = "stopped by you"
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                runner = null
            }

            TestState.finish(report?.absolutePath, error)
            val done = if (report != null) getString(R.string.notif_done, cfg.appLabel) else getString(R.string.notif_failed, error ?: "")
            stopForeground(STOP_FOREGROUND_REMOVE)
            notify(buildNotification(done, running = false, reportPath = report?.absolutePath), id = DONE_ID)
            report?.let { openReport(it) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Bring the report to the front. The shell may start activities even while we are in the background. */
    private fun openReport(file: File) {
        val out = Shell.tryExec("am start -n $packageName/${ReportActivity::class.java.name} --es file '${file.absolutePath}'", 10_000)
        if (out.contains("Error", ignoreCase = true)) {
            // The notification still opens the report when tapped.
            TestState.post("Tap the notification to open the report.")
        }
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(RUN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(RUN_ID, n)
        }
    }

    private fun notify(n: Notification, id: Int = RUN_ID) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (_: SecurityException) {
            // Notifications not allowed: the test still runs.
        }
    }

    private fun buildNotification(text: String, running: Boolean, reportPath: String? = null): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = if (reportPath != null) {
            Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_FILE, reportPath)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_perf)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setAutoCancel(!running)
            .setContentIntent(PendingIntent.getActivity(this, if (reportPath != null) 2 else 1, open, flags))
        if (running) {
            val stop = Intent(this, TestService::class.java).setAction(ACTION_STOP)
            b.addAction(0, getString(R.string.stop), PendingIntent.getService(this, 3, stop, flags))
        }
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "com.perfpilot.mobile.STOP"
        private const val CHANNEL = "tests"
        private const val RUN_ID = 1
        private const val DONE_ID = 2

        fun start(context: Context, cfg: TestConfig) {
            ContextCompat.startForegroundService(context, cfg.toIntent(Intent(context, TestService::class.java)))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TestService::class.java).setAction(ACTION_STOP))
        }
    }
}
