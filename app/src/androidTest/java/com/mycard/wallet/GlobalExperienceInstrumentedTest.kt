package com.mycard.wallet

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mycard.wallet.data.*
import com.mycard.wallet.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** Non-destructive device acceptance and OS frame timings. No production telemetry/dependencies. */
@RunWith(AndroidJUnit4::class)
class GlobalExperienceInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val vm get()=ViewModelProvider(compose.activity)[WalletViewModel::class.java]
    private val repo get()=vm.repository
    private var original: List<WalletCard>?=null
    private var prefs: WalletSettings?=null
    private val own=mutableListOf<WalletCard>()
    private val images=mutableListOf<String>()
    private val marker="全局-${UUID.randomUUID().toString().take(8)}"
    @Before fun prepare() {
        runBlocking {
            val settings=Preferences(context).flow.first()
            Assume.assumeFalse("Use an unlocked test installation",settings.privacyLock)
            prefs=settings; original=repo.all()
            val bitmap=Bitmap.createBitmap(1586,1000,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(40,65,90))
            val bytes=ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
            bitmap.recycle()
            try { repeat(2) { images+=repo.writeImage(bytes) } } finally { bytes.fill(0) }
            val start=(original!!.maxOfOrNull{it.position} ?: -1)+1
            repeat(40) { i -> own+=WalletCard(bank=if(i%3==0) "中信银行" else "中国工商银行", nickname="收藏 ${i+1}",lastFour=(9500+i).toString(),
                network=Network.entries[i%5], template=i%16, notes="全局交互测试", tags=if(i==5) "$marker-solo" else marker,
                position=start+i, faceMode=if(i%20==0) FaceMode.IMAGE else FaceMode.TEMPLATE,
                imagePath=if(i%20==0) images[i/20] else null) }
            repo.merge(own)
            Preferences(context).boolean("animations",true)
            Preferences(context).layout(CardLayout.STACK)
            Preferences(context).palette(AppPalette.WHITE)
        }
        compose.waitUntil(15_000){compose.onAllNodesWithText(Copy.collection).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription(Copy.search).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(marker)
        compose.waitUntil(15_000){compose.onAllNodesWithTag("wallet-card-${own[0].id}").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun restore(): Unit = runBlocking {
        own.forEach{repo.delete(it.id)}; images.forEach{repo.discardIfUnused(it)}
        prefs?.let { saved ->
            val p=Preferences(context)
            p.text("theme",saved.theme.name);p.boolean("stacked",saved.stacked)
            p.boolean("tailBadge",saved.tailBadge);p.boolean("animations",saved.animations)
            p.boolean("privacyLock",saved.privacyLock);p.text("sort",saved.sort.name)
            saved.paletteOverride?.let{p.text("palette",it.name)}
            saved.followSystem?.let{p.boolean("followSystem",it)}
            saved.layoutOverride?.let{p.layout(it)}
            assertEquals(saved,p.flow.first())
        }
        original?.let { assertEquals(it.sortedBy{c->c.id},repo.all().sortedBy{c->c.id}) }
    }
    private fun choose(layout: CardLayout) {
        compose.onNodeWithTag("home-layout-${layout.name}").performClick()
        compose.waitUntil(10_000){vm.settings.value?.layout==layout};compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(context.cacheDir,"qa-ui-$name.png").outputStream().use{bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap!!.recycle()
    }
    @Test fun threeArrangementsThemesFilteringAndRecreationRemainInteractive() {
        for(layout in CardLayout.entries) {
            choose(layout)
            if(layout==CardLayout.STACK || layout==CardLayout.LIST) compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(0)
            else compose.onNodeWithTag("wallet-pager").performScrollToIndex(0)
            var node=compose.onNodeWithTag("wallet-card-${own[0].id}")
            if(layout==CardLayout.STACK) { node.performClick(); node.assertIsSelected() }
            capture("layout-${layout.name}")
            if(layout==CardLayout.ALBUM) {
                compose.onNodeWithTag("wallet-pager").performTouchInput {
                    swipeWithVelocity(Offset(width*.85f,centerY),Offset(width*.25f,centerY),endVelocity=0f,durationMillis=500)
                }
                node=compose.onNodeWithTag("wallet-card-${own[1].id}")
                node.assertIsSelected()
            }
            node.performClick(); compose.onNodeWithText(Copy.details).assertIsDisplayed()
            compose.onNodeWithContentDescription(Copy.back).performClick()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("$marker-solo")
        compose.onNodeWithTag("wallet-card-${own[5].id}").assertIsSelected().performClick()
        compose.onNodeWithText(Copy.details).assertIsDisplayed();compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithContentDescription(Copy.settings).performClick()
        for(palette in AppPalette.entries) {
            compose.onNodeWithText(palette.label).performScrollTo().performClick()
            compose.waitUntil(10_000){vm.settings.value?.palette==palette && vm.settings.value?.usesSystem==false}
            capture("theme-${palette.name}")
        }
        compose.onNodeWithContentDescription(Copy.followSystem).performScrollTo().performClick()
        compose.waitUntil(10_000){vm.settings.value?.usesSystem==true}
        compose.onNodeWithText(AppPalette.GLACIER.label).performScrollTo().performClick()
        compose.waitUntil(10_000){vm.settings.value?.usesSystem==false && vm.settings.value?.palette==AppPalette.GLACIER}
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(AppPalette.GLACIER.label).assertIsDisplayed()
        assertEquals(CardLayout.ALBUM,runBlocking{Preferences(context).flow.first().layout})
        compose.onNodeWithContentDescription(Copy.back).performClick()
        compose.onNodeWithTag("wallet-card-${own[5].id}").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextReplacement("没有此卡")
        compose.onNodeWithText(Copy.noResults).assertIsDisplayed()
    }
    private data class Timing(val total: Long,val deadline: Long,val layout: Long,val draw: Long)
    @Test fun collectFrameMetricsForFortyCardsAcrossAllLayouts() {
        val thread=HandlerThread("wallet-frame-test").apply{start()}
        val handler=Handler(thread.looper)
        val active=AtomicReference<CardLayout?>(null)
        val samples=CardLayout.entries.associateWith{Collections.synchronizedList(mutableListOf<Timing>())}
        val drops=CardLayout.entries.associateWith{java.util.concurrent.atomic.AtomicInteger(0)}
        val listener=Window.OnFrameMetricsAvailableListener { _, frame, lost ->
            active.get()?.let { mode ->
                val total=frame.getMetric(FrameMetrics.TOTAL_DURATION)
                if(frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L && total>0) samples.getValue(mode).add(Timing(total,
                    if(android.os.Build.VERSION.SDK_INT>=31)frame.getMetric(FrameMetrics.DEADLINE) else -1,
                    frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),frame.getMetric(FrameMetrics.DRAW_DURATION)))
                drops.getValue(mode).addAndGet(lost)
            }
        }
        compose.runOnIdle{compose.activity.window.addOnFrameMetricsAvailableListener(listener,handler)}
        try {
            val results=JSONArray()
            for(layout in CardLayout.entries) {
                choose(layout); active.set(layout)
                repeat(8) {
                    if(layout==CardLayout.STACK || layout==CardLayout.LIST)
                        compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performTouchInput{swipeUp(durationMillis=400)}
                    else compose.onNodeWithTag("wallet-pager").performTouchInput {
                        swipeWithVelocity(Offset(width*.85f,centerY),Offset(width*.25f,centerY),endVelocity=0f,durationMillis=500)
                    }
                }
                compose.waitForIdle()
                val barrier=CountDownLatch(1);handler.post{barrier.countDown()};assertTrue(barrier.await(5,TimeUnit.SECONDS))
                active.set(null)
                val frames=synchronized(samples.getValue(layout)){samples.getValue(layout).toList()}
                assertTrue("FrameMetrics unavailable for $layout",frames.size>=10)
                val times=frames.map{it.total/1e6}.sorted()
                fun percentile(q: Double)=times[(ceil(times.size*q).toInt()-1).coerceIn(times.indices)]
                val budgeted=frames.filter{it.deadline>0}
                results.put(JSONObject().put("layout",layout.name).put("frames",frames.size).put("p50Ms",percentile(.50))
                    .put("p95Ms",percentile(.95)).put("maxMs",times.last()).put("deadlineSamples",budgeted.size)
                    .put("missedDeadlinePercent",if(budgeted.isEmpty()) JSONObject.NULL else budgeted.count{it.total>=it.deadline}*100.0/budgeted.size)
                    .put("layoutP95Ms",frames.map{it.layout/1e6}.sorted()[(ceil(frames.size*.95).toInt()-1).coerceIn(frames.indices)])
                    .put("metricDeliveryDrops",drops.getValue(layout).get()))
            }
            File(context.cacheDir,"ui-frame-metrics.json").writeText(JSONObject().put("version",BuildConfig.VERSION_NAME)
                .put("buildType","debug").put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("cards",40).put("includesEncryptedImages",true).put("results",results).toString(2))
        } finally {
            active.set(null)
            compose.runOnIdle{compose.activity.window.removeOnFrameMetricsAvailableListener(listener)}
            thread.quitSafely()
        }
    }

    /**
     * Same Window.FrameMetrics harness as the arrangement baseline, for the two interactions that
     * move a single card: stacked expand / collapse and a long-press reorder drag. Writes its own
     * file so the arrangement numbers stay intact. Idle frames are included, which is why P50 alone
     * is not the headline: the spikes show up in P95 / max.
     */
    @Test fun collectFrameMetricsForStackExpandAndReorderDrag() {
        val thread=HandlerThread("wallet-frame-interaction-test").apply{start()}
        val handler=Handler(thread.looper)
        val active=AtomicReference<String?>(null)
        val names=listOf("STACK_EXPAND_COLLAPSE","REORDER_DRAG")
        val samples=names.associateWith{Collections.synchronizedList(mutableListOf<Timing>())}
        val drops=names.associateWith{java.util.concurrent.atomic.AtomicInteger(0)}
        val listener=Window.OnFrameMetricsAvailableListener { _, frame, lost ->
            active.get()?.let { name ->
                val total=frame.getMetric(FrameMetrics.TOTAL_DURATION)
                if(frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L && total>0) samples.getValue(name).add(Timing(total,
                    if(android.os.Build.VERSION.SDK_INT>=31)frame.getMetric(FrameMetrics.DEADLINE) else -1,
                    frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),frame.getMetric(FrameMetrics.DRAW_DURATION)))
                drops.getValue(name).addAndGet(lost)
            }
        }
        compose.runOnIdle{compose.activity.window.addOnFrameMetricsAvailableListener(listener,handler)}
        try {
            val results=JSONArray()
            fun report(name: String) {
                val frames=synchronized(samples.getValue(name)){samples.getValue(name).toList()}
                assertTrue("FrameMetrics unavailable for $name",frames.size>=5)
                val times=frames.map{it.total/1e6}.sorted()
                fun percentile(q: Double)=times[(ceil(times.size*q).toInt()-1).coerceIn(times.indices)]
                val budgeted=frames.filter{it.deadline>0}
                results.put(JSONObject().put("segment",name).put("frames",frames.size).put("p50Ms",percentile(.50))
                    .put("p95Ms",percentile(.95)).put("maxMs",times.last()).put("deadlineSamples",budgeted.size)
                    .put("missedDeadlinePercent",if(budgeted.isEmpty()) JSONObject.NULL else budgeted.count{it.total>=it.deadline}*100.0/budgeted.size)
                    .put("drawP95Ms",frames.map{it.draw/1e6}.sorted()[(ceil(frames.size*.95).toInt()-1).coerceIn(frames.indices)])
                    .put("metricDeliveryDrops",drops.getValue(name).get()))
            }
            choose(CardLayout.STACK)
            compose.onNode(hasScrollToIndexAction() and hasTestTag("wallet-list")).performScrollToIndex(0)
            compose.waitForIdle()
            active.set("STACK_EXPAND_COLLAPSE")
            // Each click selects a collapsed card (expand) and collapses the previously selected one.
            repeat(6){ i -> compose.onNodeWithTag("wallet-card-${own[i].id}").performClick(); compose.waitForIdle() }
            val first=CountDownLatch(1);handler.post{first.countDown()};assertTrue(first.await(5,TimeUnit.SECONDS))
            active.set(null); report("STACK_EXPAND_COLLAPSE")

            compose.onNodeWithContentDescription(Copy.reorder).performClick()
            compose.onNodeWithText(Copy.reorderHint).assertIsDisplayed()
            compose.waitForIdle()
            active.set("REORDER_DRAG")
            repeat(6) {
                compose.onAllNodesWithContentDescription(Copy.reorder).onFirst().performTouchInput {
                    down(center); advanceEventTime(600)
                    moveBy(Offset(0f,180f),delayMillis=250); moveBy(Offset(0f,180f),delayMillis=250)
                    moveBy(Offset(0f,-120f),delayMillis=200); up()
                }
                compose.waitForIdle()
            }
            val second=CountDownLatch(1);handler.post{second.countDown()};assertTrue(second.await(5,TimeUnit.SECONDS))
            active.set(null); report("REORDER_DRAG")
            compose.onNodeWithContentDescription(Copy.back).performClick()
            File(context.cacheDir,"ui-frame-metrics-interactions.json").writeText(JSONObject().put("version",BuildConfig.VERSION_NAME)
                .put("buildType","debug").put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("cards",40).put("animations",true).put("results",results).toString(2))
        } finally {
            active.set(null)
            compose.runOnIdle{compose.activity.window.removeOnFrameMetricsAvailableListener(listener)}
            thread.quitSafely()
        }
    }
}
