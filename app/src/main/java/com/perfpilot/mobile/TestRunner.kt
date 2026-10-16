package com.perfpilot.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

data class TestConfig(
    val packageName: String,
    val appLabel: String,
    val iterations: Int,
    val warmup: Int,
    val mode: String, // "monkey" or "manual"
    val monkeyEvents: Int,
    val manualSeconds: Int,
    val intervalSeconds: Int = 2,
    val seed: Int = 42,
) {
    fun toIntent(intent: Intent): Intent = intent.apply {
        putExtra("pkg", packageName)
        putExtra("label", appLabel)
        putExtra("iterations", iterations)
        putExtra("warmup", warmup)
        putExtra("mode", mode)
        putExtra("monkeyEvents", monkeyEvents)
        putExtra("manualSeconds", manualSeconds)
    }

    companion object {
        fun fromIntent(i: Intent) = TestConfig(
            packageName = i.getStringExtra("pkg") ?: "",
            appLabel = i.getStringExtra("label") ?: "",
            iterations = i.getIntExtra("iterations", 5),
            warmup = i.getIntExtra("warmup", 1),
            mode = i.getStringExtra("mode") ?: "monkey",
            monkeyEvents = i.getIntExtra("monkeyEvents", 400),
            manualSeconds = i.getIntExtra("manualSeconds", 20),
        )
    }
}

class CancelledException : Exception("Test stopped")

/**
 * Runs the test through the Shizuku shell. Blocking: call from a background thread.
 * Produces the same raw result shape as the desktop CLI; scoring and the report are done by report.html.
 */
class TestRunner(
    private val context: Context,
    private val cfg: TestConfig,
    private val progress: (String) -> Unit,
) {
    @Volatile
    private var cancelled = false

    /** Linux uid of the app under test, used to find its network traffic. */
    private var uid = -1

    /** Servers the app connected to, with the runs they were seen in. */
    private val servers = LinkedHashMap<NetProbe.Server, MutableSet<Int>>()

    fun cancel() {
        cancelled = true
        // Stop a monkey run that is in progress
        Shell.tryExec("pkill -f com.android.commands.monkey", 5_000)
    }

    private fun checkCancelled() {
        if (cancelled) throw CancelledException()
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0) {
            checkCancelled()
            val step = minOf(left, 250L)
            Thread.sleep(step)
            left -= step
        }
    }

    private fun prop(name: String) = Shell.tryExec("getprop $name").trim()

    fun run(): JSONObject {
        val pkg = cfg.packageName
        require(Regex("""^[\w.]+$""").matches(pkg)) { "Invalid package name" }

        progress("Reading device and app info…")
        val cores = Shell.tryExec("nproc").trim().toIntOrNull()
        val clockTicks = Shell.tryExec("getconf CLK_TCK").trim().toIntOrNull()?.takeIf { it > 0 } ?: 100
        val device = JSONObject().apply {
            put("serial", JSONObject.NULL)
            put("manufacturer", prop("ro.product.manufacturer"))
            put("model", prop("ro.product.model"))
            put("android", prop("ro.build.version.release"))
            put("sdk", prop("ro.build.version.sdk"))
            put("abi", prop("ro.product.cpu.abi"))
            put("cores", cores ?: JSONObject.NULL)
        }

        val dump = Shell.exec("dumpsys package $pkg")
        if (!dump.contains("Package [$pkg]")) throw IllegalStateException("$pkg is not installed")
        val component = Parsers.resolveActivity(Shell.tryExec("cmd package resolve-activity --brief $pkg"))
            ?: throw IllegalStateException("Could not find a launch screen for $pkg")
        val app = JSONObject().apply {
            put("package", pkg)
            put("label", cfg.appLabel)
            put("versionName", Parsers.versionName(dump) ?: JSONObject.NULL)
            put("versionCode", Parsers.versionCode(dump) ?: JSONObject.NULL)
            put("component", component)
        }
        uid = try {
            context.packageManager.getApplicationInfo(pkg, 0).uid
        } catch (_: Exception) {
            Regex("""userId=(\d+)""").find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
        NetProbe.grantUsageAccess(context)

        for (w in 1..cfg.warmup) {
            checkCancelled()
            progress("Warm-up launch $w of ${cfg.warmup} (not counted)")
            Shell.tryExec("am start -S -W -n $component", 90_000)
            sleepMs(1500)
        }

        val iterations = JSONArray()
        try {
            for (i in 1..cfg.iterations) {
                checkCancelled()
                progress("Run $i of ${cfg.iterations}: launching")
                iterations.put(runOnce(i, component, clockTicks))
            }
        } catch (e: CancelledException) {
            // Stopped early: keep a report of the runs that finished, if there are any.
            if (iterations.length() == 0) throw e
            progress("Stopped after ${iterations.length()} of ${cfg.iterations} runs. Saving those.")
        } finally {
            Shell.tryExec("am force-stop $pkg", 10_000)
        }

        return JSONObject().apply {
            put("demo", false)
            put("name", JSONObject.NULL)
            put("createdAt", isoNow())
            put("app", app)
            put("device", device)
            put("config", JSONObject().apply {
                put("iterations", iterations.length())
                put("warmup", cfg.warmup)
                put(
                    "journey",
                    if (cfg.mode == "monkey") "Automatic taps, ${cfg.monkeyEvents} events (seed ${cfg.seed})"
                    else "Manual, ${cfg.manualSeconds} s"
                )
                put("intervalS", cfg.intervalSeconds)
            })
            put("iterations", iterations)
            put("network", networkSummary())
        }
    }

    /** Servers seen across runs, with a name lookup and a connect-time check from this phone. */
    private fun networkSummary(): JSONObject {
        if (servers.isNotEmpty()) progress("Checking the servers the app used…")
        val list = JSONArray()
        servers.entries
            .sortedByDescending { it.value.size }
            .take(12)
            .forEach { (server, runs) ->
                list.put(
                    JSONObject()
                        .put("ip", server.ip)
                        .put("port", server.port)
                        .put("host", NetProbe.hostName(server.ip) ?: JSONObject.NULL)
                        .put("runs", runs.size)
                        .put("connectMs", NetProbe.connectMs(server.ip, server.port) ?: JSONObject.NULL)
                )
            }
        return JSONObject()
            .put("servers", list)
            .put("serversTotal", servers.size)
            .put("apiTag", NetProbe.API_TAG)
    }

    /** Toast + vibration so manual runs are easy to follow from inside the tested app. */
    private fun cue(message: String, pulses: Int) {
        val ctx = context.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)!!.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // off/on pairs: one or more 150 ms pulses
            val timings = LongArray(pulses * 2) { i -> if (i % 2 == 1) 150L else if (i == 0) 0L else 120L }
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (_: Throwable) {
        }
    }

    private fun runOnce(index: Int, component: String, clockTicks: Int): JSONObject {
        val pkg = cfg.packageName
        val result = JSONObject().put("index", index)
        val started = System.currentTimeMillis()
        val bytesBefore = if (uid >= 0) NetProbe.uidBytes(context, uid) else null
        try {
            val launch = Shell.exec("am start -S -W -n $component", 90_000)
            val cold = Parsers.amStart(launch) ?: throw IllegalStateException("Could not read the launch time")
            result.put("coldStart", cold)
            progress("Run $index: cold start ${cold.getInt("totalMs")} ms")
            sleepMs(2000)
            Shell.tryExec("dumpsys gfxinfo $pkg reset")

            val sampler = Sampler(pkg, cfg.intervalSeconds * 1000L, clockTicks)
            sampler.start()
            try {
                if (cfg.mode == "monkey") {
                    progress("Run $index: tapping through the app")
                    Shell.exec(
                        "monkey -p $pkg -s ${cfg.seed} --pct-syskeys 0 --pct-appswitch 0 --throttle 250 ${cfg.monkeyEvents}",
                        10 * 60_000L
                    )
                } else {
                    cue("Run $index of ${cfg.iterations}: use the app now (${cfg.manualSeconds} s)", 1)
                    for (s in cfg.manualSeconds downTo 1) {
                        if (s == cfg.manualSeconds || s % 5 == 0 || s <= 3) progress("Run $index: use the app — $s s left")
                        sleepMs(1000)
                    }
                    cue(
                        if (index < cfg.iterations) "Run $index done. The app will restart for run ${index + 1}."
                        else "Last run done. Building the report…",
                        2
                    )
                }
            } finally {
                result.put("samples", sampler.stop())
                for (server in sampler.seen) servers.getOrPut(server) { HashSet() }.add(index)
            }
            checkCancelled()

            val frames = Parsers.gfxinfo(Shell.tryExec("dumpsys gfxinfo $pkg"))
            result.put("frames", frames)
            result.put("journey", cfg.mode)
            progress("Run $index: ${frames.optInt("totalFrames")} frames, ${frames.opt("jankPct")}% janky")

            val bytesAfter = if (uid >= 0) NetProbe.uidBytes(context, uid) else null
            if (bytesBefore != null && bytesAfter != null) {
                result.put(
                    "network",
                    JSONObject()
                        .put("rxBytes", (bytesAfter[0] - bytesBefore[0]).coerceAtLeast(0))
                        .put("txBytes", (bytesAfter[1] - bytesBefore[1]).coerceAtLeast(0))
                        .put("servers", sampler.seen.size)
                )
            }
            result.put("api", NetProbe.apiCalls(started))
            val crashes = NetProbe.crashes(pkg, started)
            result.put("crashes", crashes)
            if (crashes.length() > 0) progress("Run $index: the app crashed or froze (${crashes.length()} event${if (crashes.length() == 1) "" else "s"})")
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            result.put("error", (e.message ?: e.toString()).lines().first())
            progress("Run $index failed: ${result.getString("error")}")
        }
        result.put("durationS", ((System.currentTimeMillis() - started) / 100.0).roundToLong() / 10.0)
        return result
    }

    /** Samples CPU (100 = one core) and memory (PSS MB) on its own thread. */
    private inner class Sampler(private val pkg: String, private val intervalMs: Long, private val clockTicks: Int) {
        private val samples = JSONArray()
        val seen: MutableSet<NetProbe.Server> = java.util.Collections.synchronizedSet(HashSet())
        @Volatile
        private var running = true
        private var thread: Thread? = null

        fun start() {
            val t0 = System.currentTimeMillis()
            thread = Thread {
                var prevTicks: Long? = null
                var prevTime = 0L
                while (running) {
                    val begin = System.currentTimeMillis()
                    try {
                        val pid = Shell.tryExec("pidof $pkg", 5_000).trim().split(Regex("""\s+""")).firstOrNull()
                        if (!pid.isNullOrEmpty() && pid.all { c -> c.isDigit() }) {
                            val ticks = Parsers.procStatTicks(Shell.tryExec("cat /proc/$pid/stat", 5_000))
                            val now = System.currentTimeMillis()
                            var cpu: Double? = null
                            if (ticks != null) {
                                val prev = prevTicks
                                if (prev != null && ticks >= prev && now > prevTime) {
                                    val secs = (now - prevTime) / 1000.0
                                    cpu = Math.round((ticks - prev) / clockTicks.toDouble() / secs * 1000) / 10.0
                                }
                                prevTicks = ticks
                                prevTime = now
                            }
                            val mem = Parsers.meminfoMB(Shell.tryExec("dumpsys meminfo $pkg", 20_000))
                            val sample = JSONObject()
                                .put("t", Math.round((now - t0) / 100.0) / 10.0)
                                .put("cpu", cpu ?: JSONObject.NULL)
                                .put("memMB", mem ?: JSONObject.NULL)
                            synchronized(samples) { samples.put(sample) }
                        }
                        if (uid >= 0) seen.addAll(NetProbe.connections(uid))
                    } catch (_: Throwable) {
                    }
                    val wait = intervalMs - (System.currentTimeMillis() - begin)
                    if (wait > 0 && running) {
                        try {
                            Thread.sleep(wait)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }.apply {
                this.name = "perfpilot-sampler"
                this.isDaemon = true
                this.start()
            }
        }

        fun stop(): JSONArray {
            running = false
            thread?.interrupt()
            thread?.join(25_000)
            return synchronized(samples) { JSONArray(samples.toString()) }
        }
    }

    companion object {
        fun isoNow(): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date())
        }
    }
}
