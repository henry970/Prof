package com.perfpilot.mobile

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Network and log probes for the app under test. HTTPS contents can't be read from outside the app,
 * so this records data used, which servers it connects to, and API timings the app logs itself.
 */
object NetProbe {

    /** Log tag an app can write to so its API calls appear in the report: "GET /v1/items 200 184ms". */
    const val API_TAG = "PerfPilotApi"

    data class Server(val ip: String, val port: Int)

    /** Lets PerfPilot read per-app data usage. Granted through the Shizuku shell, no prompt needed. */
    fun grantUsageAccess(context: Context) {
        Shell.tryExec("appops set ${context.packageName} GET_USAGE_STATS allow", 10_000)
    }

    /** Total bytes [received, sent] by [uid] since boot of the stats history, or null if unavailable. */
    fun uidBytes(context: Context, uid: Int): LongArray? = try {
        // Flush the latest counters so the totals include the run that just happened.
        Shell.tryExec("dumpsys netstats --poll", 15_000)
        val nsm = context.getSystemService(NetworkStatsManager::class.java)!!
        var rx = 0L
        var tx = 0L
        var any = false
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val stats = try {
                nsm.queryDetailsForUid(type, null, 0, Long.MAX_VALUE, uid)
            } catch (_: Exception) {
                continue
            }
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                rx += bucket.rxBytes
                tx += bucket.txBytes
            }
            stats.close()
            any = true
        }
        if (any) longArrayOf(rx, tx) else null
    } catch (_: Throwable) {
        null
    }

    /** Open TCP connections owned by [uid], from /proc/net/tcp and tcp6. */
    fun connections(uid: Int): Set<Server> {
        val out = HashSet<Server>()
        for (file in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            for (line in Shell.tryExec("cat $file", 5_000).lines().drop(1)) {
                val f = line.trim().split(Regex("""\s+"""))
                if (f.size < 8 || f[7].toIntOrNull() != uid) continue
                // 01 = established, 02 = connecting
                if (f[3] != "01" && f[3] != "02") continue
                val (hexIp, hexPort) = f[2].split(":").takeIf { it.size == 2 } ?: continue
                val ip = decodeIp(hexIp) ?: continue
                val port = hexPort.toIntOrNull(16) ?: continue
                out.add(Server(ip, port))
            }
        }
        return out
    }

    /** /proc/net addresses are stored as 32-bit little-endian words. */
    private fun decodeIp(hex: String): String? {
        val words = hex.chunked(8)
        if (words.isEmpty() || words.any { it.length != 8 }) return null
        val bytes = words.flatMap { w -> (3 downTo 0).map { i -> w.substring(i * 2, i * 2 + 2).toInt(16) } }
        if (bytes.size == 4) return ipv4(bytes)
        if (bytes.size != 16) return null
        // IPv4-mapped IPv6 (::ffff:a.b.c.d)
        if (bytes.subList(0, 10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) return ipv4(bytes.subList(12, 16))
        return bytes.chunked(2).joinToString(":") { Integer.toHexString((it[0] shl 8) or it[1]) }
    }

    private fun ipv4(b: List<Int>): String? {
        if (b[0] == 127 || b.all { it == 0 }) return null // loopback / unbound
        return b.joinToString(".")
    }

    /** Reverse-DNS name for an IP, or null. Often the hosting provider's name, not the app's domain. */
    fun hostName(ip: String): String? = try {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
    } catch (_: Exception) {
        null
    }

    /** Time to open a TCP connection to a server from this phone, in ms. */
    fun connectMs(ip: String, port: Int, timeoutMs: Int = 3000): Long? = try {
        Socket().use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000
        }
    } catch (_: Exception) {
        null
    }

    /** logcat -T takes "seconds.millis" since the epoch. */
    fun logTime(epochMs: Long) = String.format(java.util.Locale.US, "%d.%03d", epochMs / 1000, epochMs % 1000)

    /** API calls the app logged under [API_TAG] since [sinceMs]. */
    fun apiCalls(sinceMs: Long): JSONArray {
        val out = JSONArray()
        val text = Shell.tryExec("logcat -d -T ${logTime(sinceMs)} -s $API_TAG:V", 15_000)
        val re = Regex("""$API_TAG\s*:\s*([A-Z]+)\s+(\S+)\s+(\d{3})\s+(\d+)\s*ms""")
        for (line in text.lines()) {
            val m = re.find(line) ?: continue
            out.put(
                JSONObject()
                    .put("method", m.groupValues[1])
                    .put("path", m.groupValues[2].substringBefore('?'))
                    .put("status", m.groupValues[3].toInt())
                    .put("ms", m.groupValues[4].toInt())
            )
        }
        return out
    }

    /** Crashes and "app not responding" events for [pkg] since [sinceMs], from the system event log. */
    fun crashes(pkg: String, sinceMs: Long): JSONArray {
        val out = JSONArray()
        val text = Shell.tryExec("logcat -d -b events -T ${logTime(sinceMs)} -s am_crash:V am_anr:V", 15_000)
        for (line in text.lines()) {
            if (!line.contains(pkg)) continue
            val type = when {
                line.contains("am_crash") -> "crash"
                line.contains("am_anr") -> "anr"
                else -> continue
            }
            val detail = line.substringAfter('[', line).substringBeforeLast(']').take(300)
            out.put(JSONObject().put("type", type).put("detail", detail))
        }
        return out
    }
}
