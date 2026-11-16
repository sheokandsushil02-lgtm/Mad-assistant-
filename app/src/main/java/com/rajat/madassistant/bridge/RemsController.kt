package com.rajat.madassistant.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/**
 * MAD Memory -> REMS Business automation controller.
 *
 * REMS map (verified from rems-business source code):
 *  - Bottom nav : "Overview", "Item List", "Khata Pro", "Billing"
 *  - Home cards : "Item List", "Khata Pro", "Billing & Warranty Suite", "Call REMS"
 *  - Khata FAB  : "नया ग्राहक जोड़ें"
 *  - Bill tabs  : "नया बिल", "वारंटी बिल", "बिल इतिहास", "आइटम डेटाबेस"
 */
class RemsController(
    private val service: AccessibilityService,
    private val speak: (String) -> Unit
) {

companion object {
        const val REMS_PACKAGE = "com.aistudio.remsbusiness.kpxwza"
    }

private val handler = Handler(Looper.getMainLooper())

/** Main entry — true return karta hai agar command REMS se judi thi. */
    fun tryHandle(rawCommand: String): Boolean {
        val c = rawCommand.trim().lowercase()
        fun has(vararg keys: String) = keys.any { c.contains(it) }

return when {
            has("naya grahak", "naya customer", "grahak jodo", "naya khata") ->
                dispatch("Naya grahak form khol raha hoon.") {
                    navThen(listOf("Khata Pro"), listOf("नया ग्राहक जोड़ें"), "Grahak form khul gaya.")
                }

has("warranty", "waranti") ->
                dispatch("Warranty screen khol raha hoon.") {
                    navThen(listOf("Billing"), listOf("वारंटी बिल"), "Warranty form khul gaya.")
                }

has("purane bill", "bill history", "bill list") ->
                dispatch("Bill history khol raha hoon.") {
                    navThen(listOf("Billing"), listOf("बिल इतिहास"), "Bill history khul gayi.")
                }

has("item database", "samagri database", "product database") ->
                dispatch("Item database khol raha hoon.") {
                    navThen(listOf("Billing"), listOf("आइटम डेटाबेस"), "Item database khul gaya.")
                }

has("naya bill", "bill banao", "billing", "bill kholo") ->
                dispatch("Billing khol raha hoon.") {
                    navThen(listOf("Billing"), null, "Naya bill form khul gaya.")
                }

has("item list", "samagri list", "items list") ->
                dispatch("Item List khol raha hoon.") {
                    navThen(listOf("Item List"), null, "Item List khul gaya.")
                }

has("khata") ->
                dispatch("Khata Book khol raha hoon.") {
                    navThen(listOf("Khata Pro"), null, "Khata Book khul gaya.")
                }

has("call rems", "rems ko call") ->
                dispatch("REMS ko call kar raha hoon.") {
                    navThen(listOf("Overview"), listOf("Call REMS"), "Call kar raha hoon.")
                }

has("rems kholo", "rems open", "rems chalu", "rems khol") ->
                dispatch("REMS Business khol raha hoon.") {
                    ensureRemsOpen { speak("REMS Business khul gaya.") }
                }

has("overview", "rems home") ->
                dispatch("REMS Home par ja raha hoon.") {
                    navThen(listOf("Overview"), null, "Home par aa gaye.")
                }

else -> false
        }
    }

// ---------- HELPERS ----------

private fun dispatch(spoken: String, body: () -> Unit): Boolean {
        speak(spoken)
        handler.postDelayed(body, 150)
        return true
    }

/** REMS kholo (agar band hai), phir nav button tap, phir extra button tap. */
    private fun navThen(navTexts: List<String>?, thenTap: List<String>?, doneMsg: String) {
        ensureRemsOpen {
            if (navTexts == null) {
                val extra = thenTap ?: return@ensureRemsOpen
                waitAndClick(extra) { ok ->
                    speak(if (ok) doneMsg else "Button nahi mila phone screen par.")
                }
            } else {
                waitAndClick(navTexts) { navOk ->
                    if (navOk) {
                        if (thenTap != null) {
                            handler.postDelayed({
                                waitAndClick(thenTap) { ok ->
                                    speak(if (ok) doneMsg else "Button nahi mila phone screen par.")
                                }
                            }, 900)
                        } else {
                            speak(doneMsg)
                        }
                    } else {
                        speak("REMS ka navigation button nahi mila.")
                    }
                }
            }
        }
    }

private fun ensureRemsOpen(then: () -> Unit) {
        val alreadyOpen = service.rootInActiveWindow?.packageName?.toString() == REMS_PACKAGE
        if (alreadyOpen) {
            then()
            return
        }
        val launch = service.packageManager.getLaunchIntentForPackage(REMS_PACKAGE)
        if (launch == null) {
            speak("REMS Business app phone mein installed nahi hai.")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            service.startActivity(launch)
        } catch (_: Exception) {
            speak("REMS kholne mein dikkat aa gayi.")
            return
        }
        handler.postDelayed(then, 2500)
    }

/** Screen par button dhundo (kuch seconds tak retry) aur tap karo. */
    private fun waitAndClick(texts: List<String>, attempts: Int = 20, onDone: (Boolean) -> Unit) {
        fun attempt(i: Int) {
            val node = findNodeByText(service.rootInActiveWindow, texts)
            if (node != null && clickNode(node)) {
                onDone(true)
                return
            }
            if (i >= attempts) {
                onDone(false)
                return
            }
            handler.postDelayed({ attempt(i + 1) }, 300)
        }
        attempt(0)
    }

/** Poore screen tree me text/contentDescription search (BFS). */
    private fun findNodeByText(root: AccessibilityNodeInfo?, texts: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (texts.any { t -> text.contains(t, ignoreCase = true) || desc.contains(t, ignoreCase = true) }) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

/** Node khud clickable nahi ho sak — parents par chadhte hue click karo. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = try { current.parent } catch (_: Exception) { null }
            hops++
        }
        return false
    }
}
