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

/** Non-destructive 1.1.0 to 1.1.1 update: compare every persisted field and preference. */
@RunWith(AndroidJUnit4::class)
class PatchUpgradeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot(): JSONObject = runBlocking {
        val records = JSONArray()
        CardRepository(context).all().sortedBy { it.id }.forEach { card ->
            val json = CardJson.encode(card)
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
    private fun durableWrite(file: File, text: String) {
        java.io.FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() }
    }
    /** Optional controlled data for an emulator; every original record is retained. */
    @Test fun seedUpgradeFixtures() = runBlocking {
        assertEquals(3L, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        val ledger = File(context.filesDir, "patch-fixture-ids.json")
        assertFalse("Never replace an existing fixture ledger", ledger.exists())
        val original = snapshot()
        val repo = CardRepository(context)
        val fixtures = (0..6).map { i -> com.mycard.wallet.data.WalletCard(
            bank = com.mycard.wallet.ui.BankPresets[i], lastFour = (9200+i).toString(),
            network = com.mycard.wallet.data.Network.entries[i % 5], template = 6+i,
            primary = 0xFF253748, secondary = 0xFFBED2E1, notes = "PATCH 升级测试",
            showWordmark = false, showNotes = true, logoPosition = com.mycard.wallet.data.LogoPosition.TOP_RIGHT,
            amexLayout = true, tags = "patch-upgrade-fixture", position = 1000L+i) }
        durableWrite(ledger, JSONObject().put("ids", JSONArray(fixtures.map { it.id })).put("original", original).toString())
        fixtures.forEach { repo.save(it) }
    }
    @Test fun removeOnlyOwnUpgradeFixtures() = runBlocking {
        val ledger = File(context.filesDir, "patch-fixture-ids.json")
        val saved = JSONObject(ledger.readText())
        val ids = saved.getJSONArray("ids")
        val repo = CardRepository(context)
        val all = repo.all().associateBy { it.id }
        val own = (0 until ids.length()).map { ids.getString(it) }
        assertTrue(own.all { all[it]?.tags == "patch-upgrade-fixture" })
        own.forEach { repo.delete(it) }
        assertEquals(saved.getJSONObject("original").toString(), snapshot().toString())
        assertTrue(ledger.delete())
    }
    @Test fun snapshotBeforeUpdate() {
        val actual = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals("Run this phase against the stable 1.1.0 APK", 3L, actual)
        val file = File(context.filesDir, "upgrade-1.1.0-digests.json")
        durableWrite(file, snapshot().toString())
    }
    @Test fun verifyAfterUpdate() {
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode == 4L)
        val old = JSONObject(File(context.filesDir, "upgrade-1.1.0-digests.json").readText())
        val new = snapshot()
        assertEquals(old.getJSONArray("records").toString(), new.getJSONArray("records").toString())
        assertEquals(old.getJSONObject("settings").toString(), new.getJSONObject("settings").toString())
    }
}
