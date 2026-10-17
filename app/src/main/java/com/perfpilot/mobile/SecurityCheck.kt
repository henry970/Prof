package com.perfpilot.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
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
    fun inspect(context: Context, pkg: String, launchComponent: String): JSONObject {
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

        val activities = unprotected(info(pm, pkg, PackageManager.GET_ACTIVITIES)?.activities) { (it as android.content.pm.ActivityInfo).permission }
        val services = unprotected(info(pm, pkg, PackageManager.GET_SERVICES)?.services) { (it as android.content.pm.ServiceInfo).permission }
        val receivers = unprotected(info(pm, pkg, PackageManager.GET_RECEIVERS)?.receivers) { (it as android.content.pm.ActivityInfo).permission }
        val providers = unprotected(info(pm, pkg, PackageManager.GET_PROVIDERS)?.providers) {
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
