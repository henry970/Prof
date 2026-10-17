package com.perfpilot.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only security checkup of the tested app's configuration. It only reads what Android already
 * publishes about an installed app (manifest flags, components, permissions) plus the connections
 * seen during the test. It never probes the app or its servers.
 */
object SecurityCheck {

    private val PERMISSION_NAMES = mapOf(
        "android.permission.CAMERA" to "Camera",
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.ACCESS_FINE_LOCATION" to "Precise location",
        "android.permission.ACCESS_COARSE_LOCATION" to "Approximate location",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Location in the background",
        "android.permission.READ_CONTACTS" to "Read contacts",
        "android.permission.WRITE_CONTACTS" to "Change contacts",
        "android.permission.GET_ACCOUNTS" to "Accounts on the phone",
        "android.permission.READ_SMS" to "Read SMS",
        "android.permission.SEND_SMS" to "Send SMS",
        "android.permission.RECEIVE_SMS" to "Receive SMS",
        "android.permission.READ_CALL_LOG" to "Read call log",
        "android.permission.CALL_PHONE" to "Make phone calls",
        "android.permission.READ_PHONE_STATE" to "Phone status and identity",
        "android.permission.READ_PHONE_NUMBERS" to "Phone numbers",
        "android.permission.READ_CALENDAR" to "Read calendar",
        "android.permission.WRITE_CALENDAR" to "Change calendar",
        "android.permission.READ_EXTERNAL_STORAGE" to "Read shared storage",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "Write shared storage",
        "android.permission.READ_MEDIA_IMAGES" to "Photos",
        "android.permission.READ_MEDIA_VIDEO" to "Videos",
        "android.permission.READ_MEDIA_AUDIO" to "Music and audio",
        "android.permission.BODY_SENSORS" to "Body sensors",
        "android.permission.ACTIVITY_RECOGNITION" to "Physical activity",
        "android.permission.BLUETOOTH_SCAN" to "Find nearby devices",
        "android.permission.BLUETOOTH_CONNECT" to "Connect to nearby devices",
        "android.permission.NEARBY_WIFI_DEVICES" to "Nearby Wi-Fi devices",
        "android.permission.POST_NOTIFICATIONS" to "Notifications",
    )

    /** Permissions that deserve a second look in most apps. */
    private val SENSITIVE = setOf(
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
    )

    /** Special-access permissions that aren't "dangerous" but are powerful. */
    private val SPECIAL = mapOf(
        "android.permission.SYSTEM_ALERT_WINDOW" to "Draw over other apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Install other apps",
        "android.permission.QUERY_ALL_PACKAGES" to "See every installed app",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "Access all files",
    )

    /** Component-name prefixes of common analytics, ads, attribution and session-recording SDKs. */
    private val TRACKERS = linkedMapOf(
        "com.google.android.gms.measurement" to "Google Analytics for Firebase",
        "com.google.firebase.crashlytics" to "Firebase Crashlytics",
        "com.google.android.gms.ads" to "Google AdMob",
        "com.facebook" to "Facebook SDK",
        "com.appsflyer" to "AppsFlyer",
        "com.adjust.sdk" to "Adjust",
        "io.branch" to "Branch",
        "com.mixpanel" to "Mixpanel",
        "com.amplitude" to "Amplitude",
        "com.onesignal" to "OneSignal",
        "com.clevertap" to "CleverTap",
        "com.braze" to "Braze",
        "com.appboy" to "Braze",
        "com.moengage" to "MoEngage",
        "com.segment" to "Segment",
        "io.sentry" to "Sentry",
        "com.datadog" to "Datadog",
        "com.newrelic" to "New Relic",
        "com.instabug" to "Instabug",
        "com.unity3d.ads" to "Unity Ads",
        "com.applovin" to "AppLovin",
        "com.ironsource" to "ironSource",
        "com.bytedance" to "Pangle (ByteDance)",
        "com.smartlook" to "Smartlook",
        "com.uxcam" to "UXCam",
        "com.contentsquare" to "Contentsquare",
        "com.fullstory" to "FullStory",
    )

    /** These record what users do on screen, which matters for apps showing private data. */
    private val SESSION_RECORDERS = setOf("Smartlook", "UXCam", "Contentsquare", "FullStory")

    private val STORES = mapOf(
        "com.android.vending" to "Google Play",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.amazon.venezia" to "Amazon Appstore",
        "com.huawei.appmarket" to "Huawei AppGallery",
        "com.xiaomi.market" to "Xiaomi GetApps",
        "com.heytap.market" to "OPPO App Market",
        "com.oppo.market" to "OPPO App Market",
        "com.bbk.appstore" to "vivo App Store",
        "com.transsion.phoenix" to "Palm Store",
    )

    private class Findings {
        val list = JSONArray()
        fun add(severity: String, title: String, detail: String) {
            list.put(JSONObject().put("severity", severity).put("title", title).put("detail", detail))
        }
    }

    @Suppress("DEPRECATION")
    private fun info(pm: PackageManager, pkg: String, flags: Int): PackageInfo? =
        try {
            pm.getPackageInfo(pkg, flags)
        } catch (_: Exception) {
            null // very large apps can exceed the binder limit for some component lists
        }

    @Suppress("DEPRECATION")
    fun inspect(context: Context, pkg: String, launchComponent: String, dumpsysPackage: String): JSONObject {
        val pm = context.packageManager
        val out = JSONObject()
        val f = Findings()
        val app: ApplicationInfo = info(pm, pkg, 0)?.applicationInfo
            ?: return out.put("error", "This app's details aren't visible to PerfPilot").put("findings", f.list)

        // --- Manifest flags ---
        val debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val backup = app.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        val cleartext = app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        val testOnly = app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0
        if (debuggable) f.add(
            "high", "The app is a debuggable build",
            "Anyone with USB access can attach a debugger, read the app's memory and its private files. Release builds should never be debuggable."
        )
        if (testOnly) f.add("medium", "The app is marked test-only", "Test-only builds aren't meant for real users or app stores.")
        if (cleartext) f.add(
            "medium", "Plain HTTP is allowed",
            "The manifest allows unencrypted HTTP (usesCleartextTraffic). Anything sent this way can be read or changed on public Wi-Fi. A network security config can still restrict it per domain."
        )
        if (backup) f.add(
            "low", "App data can be backed up",
            "allowBackup is on, so the app's data can be copied off the phone through Android backup. Apps holding money, health or login data usually turn it off or exclude sensitive files."
        )

        val deviceSdk = Build.VERSION.SDK_INT
        if (app.targetSdkVersion < deviceSdk - 2) f.add(
            "medium", "Targets an old Android version (API ${app.targetSdkVersion})",
            "Apps targeting older versions skip security protections added since. This phone runs API $deviceSdk."
        )

        // --- Exported components ---
        val launchClass = launchComponent.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it }
        val exported = JSONObject()
        fun unprotected(items: Array<out ComponentInfo>?, permissionOf: (ComponentInfo) -> String?): List<String> =
            (items ?: emptyArray())
                .filter { it.exported && permissionOf(it) == null && it.name != launchClass }
                .map { it.name.removePrefix("$pkg.").let { n -> if (n == it.name) n else ".$n" } }

        val allActivities = info(pm, pkg, PackageManager.GET_ACTIVITIES)?.activities
        val allServices = info(pm, pkg, PackageManager.GET_SERVICES)?.services
        val allReceivers = info(pm, pkg, PackageManager.GET_RECEIVERS)?.receivers
        val allProviders = info(pm, pkg, PackageManager.GET_PROVIDERS)?.providers
        val activities = unprotected(allActivities) { (it as android.content.pm.ActivityInfo).permission }
        val services = unprotected(allServices) { (it as ServiceInfo).permission }
        val receivers = unprotected(allReceivers) { (it as android.content.pm.ActivityInfo).permission }
        val providers = unprotected(allProviders) {
            val p = it as android.content.pm.ProviderInfo
            p.readPermission ?: p.writePermission
        }
        exported.put("activities", JSONArray(activities))
            .put("services", JSONArray(services))
            .put("receivers", JSONArray(receivers))
            .put("providers", JSONArray(providers))
        out.put("exported", exported)

        fun names(list: List<String>) = list.take(5).joinToString(", ") + if (list.size > 5) " and ${list.size - 5} more" else ""
        if (providers.isNotEmpty()) f.add(
            "high", "${providers.size} content provider${plural(providers.size)} open to other apps",
            "Other apps on the phone can query these without any permission, which can expose stored data: ${names(providers)}."
        )
        if (services.isNotEmpty()) f.add(
            "medium", "${services.size} service${plural(services.size)} open to other apps",
            "Other apps can start or bind to these without a permission: ${names(services)}. Check that each one validates its callers."
        )
        if (receivers.isNotEmpty()) f.add(
            "low", "${receivers.size} broadcast receiver${plural(receivers.size)} open to other apps",
            "Any app can send these broadcasts. Often fine for system events, but they shouldn't trigger sensitive actions: ${names(receivers)}."
        )
        if (activities.isNotEmpty()) f.add(
            "info", "${activities.size} screen${plural(activities.size)} other apps can open directly",
            "Usually deep links or share targets. Screens that show account or payment data shouldn't be reachable this way: ${names(activities)}."
        )

        // --- Powerful roles ---
        val accessibility = (allServices ?: emptyArray()).any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
        val deviceAdmin = (allReceivers ?: emptyArray()).any { it.permission == "android.permission.BIND_DEVICE_ADMIN" }
        if (accessibility) f.add(
            "medium", "Includes an accessibility service",
            "If turned on in Settings, an accessibility service can read everything on screen and act for the user. Legitimate for assistive apps; a red flag elsewhere."
        )
        if (deviceAdmin) f.add(
            "medium", "Can become a device administrator",
            "If activated, it can lock the phone, enforce password rules or wipe data. Usually only work-management or anti-theft apps need this."
        )
        out.put("accessibilityService", accessibility).put("deviceAdmin", deviceAdmin)

        // --- Third-party SDKs (from component names; SDKs without components won't show) ---
        val componentNames = listOfNotNull(allActivities, allServices, allReceivers, allProviders)
            .flatMap { arr -> arr.map { it.name } }
        val sdks = TRACKERS.entries
            .filter { e -> componentNames.any { it.startsWith(e.key) } }
            .map { it.value }
            .distinct()
        out.put("sdks", JSONArray(sdks))
        val recorders = sdks.filter { it in SESSION_RECORDERS }
        if (recorders.isNotEmpty()) f.add(
            "medium", "Records user sessions: ${recorders.joinToString(", ")}",
            "Session-recording SDKs capture taps and screens. Make sure balances, card numbers and personal details are masked in the recordings."
        )
        if (sdks.isNotEmpty()) f.add(
            "info", "${sdks.size} analytics/ads SDK${plural(sdks.size)} found",
            "${sdks.joinToString(", ")}. Each one may send usage data to its company; check they're listed in the privacy policy."
        )

        // --- Signing ---
        val cert = signingCert(pm, pkg)
        if (cert != null) {
            out.put("signer", cert.subjectX500Principal.name)
            out.put("signerSha256", sha256(cert.encoded))
            if (cert.subjectX500Principal.name.contains("CN=Android Debug")) f.add(
                "high", "Signed with a debug key",
                "This build is signed with Android's public debug key, so anyone could ship an update that replaces it. Release builds must use a private key."
            )
        }
        val scheme = Regex("""signatures=PackageSignatures\{[^}]*version:(\d+)""").find(dumpsysPackage)?.groupValues?.get(1)?.toIntOrNull()
        out.put("signatureScheme", scheme ?: JSONObject.NULL)
        if (scheme == 1 && app.minSdkVersion < 24) f.add(
            "medium", "Signed with the old v1 scheme only",
            "On Android 7.0 and older this lets tampered copies of the app pass the signature check (the Janus flaw). Sign with v2 or newer."
        )

        // --- Install source ---
        val installer = installerOf(pm, pkg)
        val store = installer?.let { STORES[it] }
        out.put("installer", store ?: installer ?: JSONObject.NULL)
        if (store == null && (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0) f.add(
            "low", "Not installed from an app store",
            "Installed by ${installer ?: "an unknown source"} (sideloaded). Sideloaded copies skip store malware checks; make sure this APK came from the developer."
        )

        // --- Permissions ---
        val granted = JSONArray()
        val sensitive = ArrayList<String>()
        val withPerms = info(pm, pkg, PackageManager.GET_PERMISSIONS)
        val requested = withPerms?.requestedPermissions ?: emptyArray()
        val reqFlags = withPerms?.requestedPermissionsFlags ?: IntArray(0)
        val special = ArrayList<String>()
        for ((i, perm) in requested.withIndex()) {
            SPECIAL[perm]?.let { special.add(it) }
            val isGranted = (reqFlags.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            if (!isGranted) continue
            val dangerous = try {
                val pi = pm.getPermissionInfo(perm, 0)
                val base = if (Build.VERSION.SDK_INT >= 28) pi.protection else pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                base == PermissionInfo.PROTECTION_DANGEROUS
            } catch (_: Exception) {
                false
            }
            if (!dangerous) continue
            val label = PERMISSION_NAMES[perm] ?: perm.substringAfterLast('.').lowercase().replace('_', ' ')
            granted.put(JSONObject().put("name", perm).put("label", label).put("sensitive", perm in SENSITIVE))
            if (perm in SENSITIVE) sensitive.add(label)
        }
        out.put("permissions", granted)
        if (sensitive.isNotEmpty()) f.add(
            "medium", "Has sensitive access: ${sensitive.joinToString(", ")}",
            "These are granted right now. Make sure each one is needed for what the app does."
        )
        if (special.isNotEmpty()) f.add(
            "low", "Requests special access: ${special.joinToString(", ")}",
            "These powerful abilities are switched on separately in Settings. Check they're needed."
        )

        out.put("targetSdk", app.targetSdkVersion)
        out.put("minSdk", app.minSdkVersion)
        out.put("debuggable", debuggable).put("allowBackup", backup).put("cleartextAllowed", cleartext)
        out.put("findings", f.list)
        return out
    }

    @Suppress("DEPRECATION")
    private fun signingCert(pm: PackageManager, pkg: String): X509Certificate? = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info(pm, pkg, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo
            if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info(pm, pkg, PackageManager.GET_SIGNATURES)?.signatures
        }
        val newest = sigs?.lastOrNull() // last entry is the current key in a rotation history
        if (newest == null) null
        else CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(newest.toByteArray())) as X509Certificate
    } catch (_: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)
    } catch (_: Exception) {
        null
    }

    /**
     * Whether the app's windows block screenshots (FLAG_SECURE), from `dumpsys window windows` filtered to
     * window headers and flag lines. Null if no window of the app was found.
     */
    fun windowSecure(windowDump: String, pkg: String): Boolean? {
        var inApp = false
        var found = false
        var secure = false
        val flag = Regex("""\bSECURE\b""")
        for (line in windowDump.lines()) {
            if (line.contains("Window #")) {
                inApp = line.contains("$pkg/") || line.contains(" $pkg}")
                if (inApp) found = true
            } else if (inApp && line.contains("fl=") && flag.containsMatchIn(line)) {
                secure = true
            }
        }
        return if (found) secure else null
    }

    private val LEAK_PATTERNS = listOf(
        Triple("Auth token (JWT)", "high", Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{6,}""")),
        Triple("Bearer token", "high", Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{16,}""")),
        Triple("Password or PIN", "high", Regex("""(?i)\b(password|passwd|pwd|pin|otp)\b\s*["']?\s*[=:]\s*["']?(?!true\b|false\b|null\b)[^\s"',}]{3,}""")),
        Triple("API key or secret", "high", Regex("""(?i)\b(api[_-]?key|secret|access[_-]?token|refresh[_-]?token|client[_-]?secret)\b\s*["']?\s*[=:]\s*["']?[A-Za-z0-9._-]{8,}""")),
        Triple("Card number", "high", Regex("""\b\d(?:[ -]?\d){12,18}\b""")),
        Triple("Email address", "low", Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
    )

    /** Shell-side pre-filter so only candidate lines cross the binder. */
    const val LOG_GREP = "grep -iE 'eyJ|bearer|passw|pwd|pin|otp|api.?key|secret|token|@|[0-9][0-9 -]{12,}[0-9]' | tail -n 800"

    /** Counts sensitive-looking values in the app's own log lines. [into]: type -> [count, severity, masked sample]. */
    fun scanLogs(text: String, into: MutableMap<String, Array<Any>>) {
        for (line in text.lines()) {
            for ((type, severity, re) in LEAK_PATTERNS) {
                val m = re.find(line) ?: continue
                if (type == "Card number" && !luhn(m.value.filter { it.isDigit() })) continue
                val entry = into.getOrPut(type) { arrayOf(0, severity, mask(m.value)) }
                entry[0] = (entry[0] as Int) + 1
            }
        }
    }

    private fun mask(v: String) = if (v.length <= 6) "•••" else v.take(4) + "•••" + v.takeLast(2)

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Adds what was observed while the app ran: screenshot protection and sensitive data in its logs. */
    fun addRuntime(security: JSONObject, secureSeen: Boolean?, leaks: Map<String, Array<Any>>) {
        val list = security.optJSONArray("findings") ?: JSONArray().also { security.put("findings", it) }
        security.put("screenshotsBlocked", secureSeen ?: JSONObject.NULL)
        if (secureSeen == false) list.put(
            JSONObject().put("severity", "low")
                .put("title", "Screens can be captured")
                .put(
                    "detail",
                    "The app's screens don't block screenshots or screen recording (FLAG_SECURE). Apps that show balances, card details or one-time codes usually block it on those screens."
                )
        )
        val leakArr = JSONArray()
        for ((type, v) in leaks) {
            leakArr.put(JSONObject().put("type", type).put("count", v[0]).put("severity", v[1]).put("sample", v[2]))
        }
        security.put("logLeaks", leakArr)
        val serious = leaks.filter { it.value[1] == "high" }
        if (serious.isNotEmpty()) list.put(
            JSONObject().put("severity", "high")
                .put("title", "Sensitive data written to the system log")
                .put(
                    "detail",
                    serious.entries.joinToString("; ") { "${it.key} x${it.value[0]} (e.g. ${it.value[2]})" } +
                        ". Crash reporters, bug reports and tools with log access can read these. Remove or redact them in release builds."
                )
        )
        leaks["Email address"]?.let {
            list.put(
                JSONObject().put("severity", "info")
                    .put("title", "Email addresses in the log")
                    .put("detail", "${it[0]} log line(s) contain an email address. Avoid logging personal data in release builds.")
            )
        }
    }

    /** Adds a finding for unencrypted connections actually seen during the test. */
    fun addObservedCleartext(security: JSONObject, servers: Collection<NetProbe.Server>) {
        val plain = servers.filter { it.port == 80 }.map { it.ip }.distinct()
        security.put("cleartextServers", JSONArray(plain))
        if (plain.isEmpty()) return
        val list = security.optJSONArray("findings") ?: JSONArray().also { security.put("findings", it) }
        list.put(
            JSONObject().put("severity", "high")
                .put("title", "Unencrypted connections seen during the test")
                .put(
                    "detail",
                    "The app connected to port 80 (plain HTTP) on: ${plain.take(5).joinToString(", ")}. " +
                        "If this carries anything other than public files, it can be read or changed on the network."
                )
        )
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
