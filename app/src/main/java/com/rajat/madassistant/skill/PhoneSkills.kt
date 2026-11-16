package com.rajat.madassistant.skill

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.rajat.madassistant.core.TargetAppManager

/**
 * Offline voice skills that talk to the phone itself:
 * open apps, WhatsApp, dial a number/contact, browse, email, time/date.
 * All triggered via Hindi/Hinglish phrases parsed from the spoken text.
 */
class PhoneSkills(private val context: Context) {

    private val targetManager = TargetAppManager(context)

    /** Returns a spoken reply, or null when this text is not a phone skill. */
    fun handle(text: String): String? {
        val t = " ${text.lowercase().trim()} "
        return when {
            // ---- WhatsApp ----
            hasAny(t, "whatsapp", "व्हाट्सएप", "वॉट्सऐप") -> openWhatsApp()
            // ---- Call ----
            hasAny(t, "call", "कॉल", "फोन कर") -> dial(text)
            // ---- Browser ----
            hasAny(t, "browser", "chrome", "ब्राउज़र", "google khol", "google खोल") -> openBrowser()
            // ---- Email ----
            hasAny(t, "email", "mail", "ईमेल", "मेल") -> openEmail()
            // ---- Time / Date ----
            hasAny(t, "time", "समय", "टाइम") && hasAny(t, "kya", "क्या", "बता", "tell") -> tellTime()
            hasAny(t, "date", "तारीख", "तारीख़") -> tellDate()
            // ---- Generic app open: "open <app>" / "<app> kholo" ----
            else -> tryOpenAppByName(t)
        }
    }

    private fun hasAny(t: String, vararg keys: String): Boolean =
        keys.any { t.contains(it) }

    private fun openWhatsApp(): String {
        return try {
            val pm = context.packageManager
            val pkg = if (isInstalled(WHATSAPP_BUSINESS)) WHATSAPP_BUSINESS else WHATSAPP
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                "WhatsApp इस phone में install नहीं है।"
            } else {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "WhatsApp खोल रहा हूँ।"
            }
        } catch (_: Exception) {
            "WhatsApp खोलने में दिक्कत आई।"
        }
    }

    private fun dial(raw: String): String {
        val number = extractPhoneNumber(raw)
        return try {
            if (number != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Dial pad खोल रहा हूँ, number: $number"
            } else {
                val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Dial pad खोल रहा हूँ।"
            }
        } catch (_: Exception) {
            "Dial pad खोलने में दिक्कत आई।"
        }
    }

    private fun openBrowser(): String {
        val actions = listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser"
        )
        for (pkg in actions) {
            if (isInstalled(pkg)) {
                val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "Browser खोल रहा हूँ।"
                }
            }
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Browser खोल रहा हूँ।"
        } catch (_: Exception) {
            "कोई browser नहीं मिला।"
        }
    }

    private fun openEmail(): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Email app खोल रहा हूँ।"
        } catch (_: Exception) {
            "Email app नहीं मिला।"
        }
    }

    private fun tellTime(): String {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        return "अभी समय $h बजकर $m मिनट $ampm है।"
    }

    private fun tellDate(): String {
        val cal = java.util.Calendar.getInstance()
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val mon = arrayOf(
            "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून",
            "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"
        )
        return "आज की तारीख $d ${mon[cal.get(java.util.Calendar.MONTH)]} है।"
    }

    private fun extractPhoneNumber(raw: String): String? {
        // Prefer digit groups of >= 10 digits (Hindi/English digits both)
        val normalized = raw
            .replace('०', '0').replace('१', '1').replace('२', '2')
            .replace('३', '3').replace('४', '4').replace('५', '5')
            .replace('६', '6').replace('७', '7').replace('८', '8')
            .replace('९', '9')
        val match = Regex("(\\d{10,13})").find(normalized) ?: return null
        return match.groupValues[1]
    }

    /** Fallback: try "open X kholo" style commands against installed launchable apps. */
    private fun tryOpenAppByName(t: String): String? {
        if (!hasAny(t, "khol", "खोल", "open", "start")) return null
        val pm = context.packageManager
        val labelList = pm.getInstalledApplications(0).map { app ->
            try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { "" }
        }
        // Simple fuzzy match: spoken phrase contains app label (3+ chars)
        val candidates = labelList.filter { it.length >= 3 && t.contains(it.lowercase()) }
        if (candidates.isEmpty()) return null
        // Pick longest matching label (most specific)
        val chosen = candidates.maxByOrNull { it.length } ?: return null
        val intent = pm.getLaunchIntentForPackage(
            pm.getInstalledApplications(0).first { app ->
                try { pm.getApplicationLabel(app).toString() == chosen } catch (_: Exception) { false }
            }.packageName
        ) ?: return null
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "$chosen खोल रहा हूँ।"
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        /**** whatsapp for business */
        private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
