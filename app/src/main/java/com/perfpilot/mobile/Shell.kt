package com.perfpilot.mobile

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Connection to the shell service that Shizuku runs for us. */
object Shell {

    enum class Status { NOT_RUNNING, OUTDATED, NO_PERMISSION, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var latch = CountDownLatch(1)

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder)
                latch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            latch = CountDownLatch(1)
        }
    }

    fun status(): Status {
        return try {
            when {
                !Shizuku.pingBinder() -> Status.NOT_RUNNING
                Shizuku.isPreV11() -> Status.OUTDATED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.READY
                else -> Status.NO_PERMISSION
            }
        } catch (_: Throwable) {
            Status.NOT_RUNNING
        }
    }

    /** Blocks (call from a background thread) until the shell service is connected. */
    fun connect(timeoutMs: Long = 10_000): Boolean {
        val current = service
        if (current != null && current.asBinder().pingBinder()) return true
        if (status() != Status.READY) return false
        val waitOn = latch
        Handler(Looper.getMainLooper()).post {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (_: Throwable) {
            }
        }
        return waitOn.await(timeoutMs, TimeUnit.MILLISECONDS) && service != null
    }

    /** Runs a shell command and returns its combined output. */
    fun exec(command: String, timeoutMs: Long = 60_000): String {
        val s = service ?: throw IllegalStateException("The Shizuku shell is not connected")
        return s.exec(command, timeoutMs) ?: ""
    }

    /** Like exec, but returns "" instead of throwing. */
    fun tryExec(command: String, timeoutMs: Long = 30_000): String =
        try {
            exec(command, timeoutMs)
        } catch (_: Throwable) {
            ""
        }
}
