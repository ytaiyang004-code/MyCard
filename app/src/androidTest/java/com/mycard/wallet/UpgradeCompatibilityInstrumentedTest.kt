package com.mycard.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mycard.wallet.data.CardJson
import com.mycard.wallet.data.CardRepository
import com.mycard.wallet.data.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Uses only APIs also present in 1.0.1: run snapshot on old APK, verify after adb install -r. */
@RunWith(AndroidJUnit4::class)
class UpgradeCompatibilityInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot(): JSONObject = runBlocking {
        val records = JSONArray()
        CardRepository(context).all().sortedBy { it.id }.forEach { card ->
            val json = CardJson.encode(card)
            listOf("showNotes", "showWordmark", "logoPosition", "amexLayout").forEach(json::remove)
            val canonical = json.keys().asSequence().sorted().joinToString("\n") { "$it=${json.get(it)}" }.toByteArray()
            val digest = try { MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) } } finally { canonical.fill(0) }
            records.put(JSONObject().put("id", card.id).put("sha256", digest))
        }
        val prefs = Preferences(context).flow.first()
        JSONObject().put("records", records).put("settings", JSONObject().apply {
            put("stacked", prefs.stacked); put("theme", prefs.theme.name); put("tailBadge", prefs.tailBadge)
            put("animations", prefs.animations); put("sort", prefs.sort.name); put("privacyLock", prefs.privacyLock)
        })
    }
    @Test fun snapshotBeforeUpdate() {
        val actual = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals("Run this phase against the stable 1.0.1 APK", 2L, actual)
        val file = File(context.cacheDir, "upgrade-1.0.1-digests.json")
        file.writeText(snapshot().toString())
    }
    @Test fun verifyAfterUpdate() {
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode > 2)
        val old = JSONObject(File(context.cacheDir, "upgrade-1.0.1-digests.json").readText())
        val new = snapshot()
        assertEquals(old.getJSONArray("records").toString(), new.getJSONArray("records").toString())
        assertEquals(old.getJSONObject("settings").toString(), new.getJSONObject("settings").toString())
    }
}
