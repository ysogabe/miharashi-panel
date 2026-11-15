package io.github.ysogabe.miharashi.panel

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/**
 * `PagerLayout` に `MotionEvent` を流して確かめる。ビューは窓に付けず、時刻は仮想時計。
 * アニメーションは `stepAnimation()` を時刻を進めてから呼んで進める(`IntroVideoTest.Demo` の流儀)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PagerLayoutTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** 受けたイベントを覚えるだけの子 */
    private class Probe(ctx: Context) : View(ctx) {
        val events = mutableListOf<Int>()
        override fun onTouchEvent(e: MotionEvent): Boolean { events += e.actionMasked; return true }
    }

    private class Rig(ctx: Context, a: View, b: View, var swallowed: Boolean = false) {
        var now = 10_000L
        val controller = PageController(60_000)
        val settled = mutableListOf<Pair<Page, PageReason>>()
        val pager = PagerLayout(ctx, a, b, controller, gestureSwallowed = { swallowed }, monotonicMs = { now })
        private var downTime = 0L

        init {
            controller.addListener(object : PageListener {
                override fun onSettled(page: Page, reason: PageReason) { settled += page to reason }
            })
            pager.measure(MeasureSpec.makeMeasureSpec(960, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY))
            pager.layout(0, 0, 960, 480)
        }

        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }
        fun down(x: Float, y: Float = 240f) { downTime = now; send(MotionEvent.ACTION_DOWN, x, y) }
        fun move(x: Float, y: Float = 240f, dt: Long = 16) { now += dt; send(MotionEvent.ACTION_MOVE, x, y) }
        fun up(x: Float, y: Float = 240f, dt: Long = 16) { now += dt; send(MotionEvent.ACTION_UP, x, y) }

        /** 水平に `steps` 回に分けて動かして離す */
        fun swipe(x0: Float, x1: Float, y: Float = 240f, steps: Int = 8, stepMs: Long = 16) {
            down(x0, y)
            for (i in 1..steps) move(x0 + (x1 - x0) * i / steps, y, stepMs)
            up(x1, y, stepMs)
        }

        /** アニメーションを最後まで進める */
        fun settle() {
            now += PagerLayout.ANIMATION_MS
            pager.stepAnimation()
        }
    }

    private fun rig(swallowed: Boolean = false): Triple<Rig, Probe, Probe> {
        val a = Probe(ctx); val b = Probe(ctx)
        return Triple(Rig(ctx, a, b, swallowed), a, b)
    }

    @Test fun restsOnAWithBOutOfSightAndNotDrawn() {
        val (r, a, b) = rig()
        assertEquals(0f, a.translationX, 0f)
        assertEquals(960f, b.translationX, 0f)
        assertEquals(View.VISIBLE, a.visibility)
        assertEquals(View.INVISIBLE, b.visibility)
        assertEquals(0f, r.pager.position, 0f)
        assertEquals(View.LAYER_TYPE_NONE, a.layerType)
    }

    @Test fun leftSwipeGoesToBAndRightSwipeBack() {
        val (r, a, b) = rig()
        r.swipe(800f, 400f, stepMs = 40)
        assertEquals(PageController.Phase.ANIMATING, r.controller.phase)
        assertTrue("アニメーション中はまだ入場ではない", r.settled.isEmpty())
        r.settle()
        assertEquals(listOf(Page.B to PageReason.SWIPE), r.settled)
        assertEquals(1f, r.pager.position, 0f)
        assertEquals(-960f, a.translationX, 0f)
        assertEquals(0f, b.translationX, 0f)
        assertEquals(View.INVISIBLE, a.visibility)
        assertEquals(View.VISIBLE, b.visibility)
        r.swipe(200f, 600f, stepMs = 40)
        r.settle()
        assertEquals(listOf(Page.B to PageReason.SWIPE, Page.A to PageReason.SWIPE), r.settled)
        assertEquals(0f, r.pager.position, 0f)
        assertEquals(0f, a.translationX, 0f)
        assertEquals(View.INVISIBLE, b.visibility)
    }

    @Test fun pageFollowsTheFingerAndBIsHalfVisibleWithoutEntering() {
        val (r, a, b) = rig()
        r.down(800f)
        r.move(560f, dt = 100)   // 240 px = 幅の 25%
        r.move(320f, dt = 100)   // 480 px: ちょうど半分
        assertEquals(0.5f, r.pager.position, 0.001f)
        assertEquals(-480f, a.translationX, 0.5f)
        assertEquals(480f, b.translationX, 0.5f)
        assertEquals(View.VISIBLE, b.visibility)
        assertEquals(View.LAYER_TYPE_HARDWARE, a.layerType)
        assertEquals(View.LAYER_TYPE_HARDWARE, b.layerType)
        assertTrue("半分見えている間は入場を出さない", r.settled.isEmpty())
        assertTrue(r.controller.userBusy)
        r.up(320f, dt = 100)
        r.now += 90
        r.pager.stepAnimation()
        assertTrue("アニメーションの途中でも入場ではない", r.settled.isEmpty())
        r.settle()
        assertEquals(1, r.settled.size)
        // 落ち着けば、レイヤーは外す
        assertEquals(View.LAYER_TYPE_NONE, a.layerType)
        assertEquals(View.LAYER_TYPE_NONE, b.layerType)
        // これ以上アニメーションを進めても、入場は繰り返さない
        r.settle(); r.settle()
        assertEquals(1, r.settled.size)
    }

    @Test fun childGetsCancelWhenSwipeIsTakenOverAndNoUp() {
        val (r, a, b) = rig()
        r.swipe(800f, 300f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), a.events)
        assertTrue(b.events.isEmpty())
        r.settle()
    }

    @Test fun shortSlowDragRevertsWithoutSettling() {
        val (r, a, _) = rig()
        r.swipe(800f, 700f, steps = 5, stepMs = 100)   // 100 px、ゆっくり
        r.settle()
        assertEquals(0f, r.pager.position, 0f)
        assertEquals(0f, a.translationX, 0f)
        assertTrue(r.settled.isEmpty())
        assertEquals(PageController.Phase.IDLE, r.controller.phase)
        assertFalse(r.controller.bVisible)
    }

    @Test fun shortFastFlickAdvances() {
        val (r, _, _) = rig()
        val density = ctx.resources.displayMetrics.density
        // 3 回 10 ms ずつで 150 px = 5000 px/s。どの密度でも 600 dp/s を超える
        assertTrue(5000f / density >= 600f)
        r.swipe(800f, 650f, steps = 3, stepMs = 10)
        r.settle()
        assertEquals(listOf(Page.B to PageReason.SWIPE), r.settled)
    }

    @Test fun longDragBeyondQuarterAdvancesEvenWhenSlow() {
        val (r, _, _) = rig()
        r.swipe(900f, 600f, steps = 6, stepMs = 200)   // 300 px(31%)、250 px/s
        r.settle()
        assertEquals(Page.B, r.controller.settledPage)
    }

    @Test fun stoppingBeforeReleaseDoesNotCountAsAFlick() {
        val (r, _, _) = rig()
        r.down(800f)
        r.move(780f, dt = 10); r.move(740f, dt = 10); r.move(700f, dt = 10)   // 速い
        r.up(700f, dt = 500)                                                  // 止めてから離す
        r.settle()
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun diagonalMovementIsNotTakenOver() {
        val (r, a, _) = rig()
        r.down(800f, 100f)
        // |dx| = 2|dy| は斜め
        r.move(600f, 200f)
        r.move(400f, 300f)
        assertEquals(0f, r.pager.position, 0f)
        r.up(400f, 300f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), a.events)
        assertEquals(PageController.Phase.IDLE, r.controller.phase)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun mostlyVerticalMovementIsNotTakenOver() {
        val (r, a, _) = rig()
        r.swipe(500f, 450f, y = 100f, steps = 1)    // 横 50 px だけ(縦 0)でも slop を超える: 奪う
        assertTrue(a.events.contains(MotionEvent.ACTION_CANCEL))
        val (r2, a2, _) = rig()
        r2.down(500f, 100f)
        r2.move(480f, 400f)
        r2.move(470f, 450f)
        r2.up(470f, 450f)
        assertFalse(a2.events.contains(MotionEvent.ACTION_CANCEL))
        assertEquals(0f, r2.pager.position, 0f)
    }

    @Test fun movementWithinTouchSlopIsNotTakenOver() {
        val (r, a, _) = rig()
        r.down(500f)
        r.move(500f - 2f)
        r.up(498f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), a.events)
        assertEquals(0f, r.pager.position, 0f)
    }

    @Test fun cannotPullPastTheEdge() {
        val (r, a, b) = rig()
        val maxPull = PagerLayout.MAX_PULL_DP * ctx.resources.displayMetrics.density
        // A でさらに右へ
        r.down(100f)
        r.move(500f); r.move(900f)
        assertEquals(View.INVISIBLE, b.visibility)
        assertTrue("端の外へはほんの少ししか動かない", a.translationX in 0f..(maxPull + 0.5f))
        assertTrue(r.pager.position <= 0f)
        r.up(900f)
        r.settle()
        assertEquals(0f, a.translationX, 0f)
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
        // B でさらに左へ
        r.swipe(800f, 300f); r.settle()
        r.down(900f)
        r.move(500f); r.move(50f)
        assertTrue(b.translationX in (-maxPull - 0.5f)..0f)
        assertTrue(r.pager.position >= 1f)
        r.up(50f)
        r.settle()
        assertEquals(0f, b.translationX, 0f)
        assertEquals(Page.B, r.controller.settledPage)
        assertEquals("引っ張って戻しても入場は繰り返さない", 1, r.settled.size)
    }

    @Test fun dragCanReverseDirectionAndRevert() {
        val (r, _, _) = rig()
        r.down(800f)
        r.move(500f, dt = 100)
        r.move(780f, dt = 100)
        r.up(780f, dt = 100)       // 20 px しか進んでいない
        r.settle()
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun gestureSwallowedByClockWakeIsNeverTakenOver() {
        val (r, a, _) = rig(swallowed = true)
        r.swipe(900f, 100f)
        assertEquals(0f, r.pager.position, 0f)
        assertFalse(a.events.contains(MotionEvent.ACTION_CANCEL))
        assertEquals(MotionEvent.ACTION_UP, a.events.last())
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
        // 次の指は普通のスワイプ
        r.swallowed = false
        r.swipe(900f, 300f)
        r.settle()
        assertEquals(Page.B, r.controller.settledPage)
    }

    @Test fun swallowedFlagTurningOnMidGestureStopsTakeoverBeforeItStarts() {
        val (r, _, _) = rig()
        r.down(900f)
        r.swallowed = true          // DOWN の後に PanelView が立てる(時計の満了直後で未描画だった場合)
        r.move(500f); r.move(100f)
        assertEquals(0f, r.pager.position, 0f)
        r.up(100f)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun touchDuringAutoAnimationLetsItFinishAndIsIgnored() {
        val (r, a, b) = rig()
        assertTrue(r.controller.goTo(Page.B, PageReason.AUTO_NOTICE, r.now))
        r.now += 60
        r.pager.stepAnimation()
        val mid = r.pager.position
        assertTrue(mid in 0.01f..0.99f)
        // 指が触れて、逆向きにドラッグする: 巻き戻さない
        r.swipe(200f, 800f, steps = 4, stepMs = 10)
        assertTrue("進行中のアニメーションを止めない", r.pager.position >= mid)
        assertTrue(a.events.isEmpty() && b.events.isEmpty())
        r.settle()
        assertEquals(listOf(Page.B to PageReason.AUTO_NOTICE), r.settled)
        assertEquals(1f, r.pager.position, 0f)
        // その後は通常のスワイプ
        r.swipe(200f, 800f, stepMs = 30)
        r.settle()
        assertEquals(Page.A, r.controller.settledPage)
        assertEquals(2, r.settled.size)
    }

    @Test fun fingerDownDuringAutoAnimationDoesNotMakeLaterMovesIntoADrag() {
        val (r, _, _) = rig()
        r.controller.goTo(Page.B, PageReason.AUTO_NOTICE, r.now)
        r.down(500f)                    // アニメーション中に DOWN
        r.settle()                      // 指を置いたまま完了
        r.move(100f); r.move(50f)       // 以降の MOVE はドラッグにならない
        assertEquals(1f, r.pager.position, 0f)
        r.up(50f)
        assertEquals(Page.B, r.controller.settledPage)
        assertEquals(PageController.Phase.IDLE, r.controller.phase)
    }

    @Test fun programmaticIdleReturnAnimatesBackToA() {
        val (r, a, _) = rig()
        r.swipe(800f, 300f); r.settle()
        r.now += 59_000
        assertFalse(r.controller.tick(r.now))
        r.now += 1_000
        assertTrue(r.controller.tick(r.now))
        r.now += 90
        r.pager.stepAnimation()
        assertTrue(a.translationX in -959f..-1f)
        r.settle()
        assertEquals(Page.A to PageReason.IDLE_RETURN, r.settled.last())
        assertEquals(0f, a.translationX, 0f)
    }

    @Test fun touchesOnAnyPageCountAsActivityForB() {
        val (r, _, _) = rig()
        r.swipe(800f, 300f); r.settle()
        r.now += 50_000
        r.down(500f); r.up(500f)        // B をタップ
        r.now += 59_000
        assertFalse(r.controller.tick(r.now))
        r.now += 1_000
        assertTrue(r.controller.tick(r.now))
    }

    @Test fun pauseSnapsBackToAWithoutAnimationAndFreesLayers() {
        val (r, a, b) = rig()
        r.down(800f); r.move(600f); r.move(300f)
        r.controller.onPause(r.now)
        assertEquals(0f, r.pager.position, 0f)
        assertEquals(0f, a.translationX, 0f)
        assertEquals(View.LAYER_TYPE_NONE, a.layerType)
        assertEquals(View.LAYER_TYPE_NONE, b.layerType)
        // 続きの指は無視
        r.move(100f); r.up(100f)
        assertEquals(0f, r.pager.position, 0f)
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun pauseWhileBIsShownReturnsToA() {
        val (r, a, _) = rig()
        r.swipe(800f, 300f); r.settle()
        r.controller.onPause(r.now)
        assertEquals(0f, a.translationX, 0f)
        assertEquals(View.VISIBLE, a.visibility)
        assertEquals(Page.A to PageReason.RESUME, r.settled.last())
    }

    @Test fun cancelledGestureNeverEntersB() {
        val (r, _, _) = rig()
        r.down(900f); r.move(500f); r.move(100f)
        r.now += 16
        r.send(MotionEvent.ACTION_CANCEL, 100f, 240f)
        r.settle()
        assertEquals(Page.A, r.controller.settledPage)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun secondFingerIsIgnoredAndDoesNotEndTheDrag() {
        val (r, _, _) = rig()
        r.down(800f)
        r.move(600f); r.move(500f)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 500f; y = 240f },
            MotionEvent.PointerCoords().apply { x = 100f; y = 100f })
        fun pointerEvent(action: Int) = MotionEvent.obtain(0, r.now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        val down2 = pointerEvent(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        r.pager.dispatchTouchEvent(down2); down2.recycle()
        val up2 = pointerEvent(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        r.pager.dispatchTouchEvent(up2); up2.recycle()
        assertEquals(PageController.Phase.DRAGGING, r.controller.phase)
        r.up(500f)
        r.settle()
        assertEquals(Page.B, r.controller.settledPage)
    }

    @Test fun edgesAreExcludedFromSystemGestures() {
        val (r, _, _) = rig()
        val rects = r.pager.systemGestureExclusionRects
        assertEquals(2, rects.size)
        assertTrue(rects.any { it.left == 0 && it.top == 0 && it.bottom == 480 && it.right > 0 })
        assertTrue(rects.any { it.right == 960 && it.top == 0 && it.bottom == 480 && it.left < 960 })
    }

    @Test fun pageDotsAreDrawnOnlyWhileVisibleAndDoNotThrow() {
        val (r, _, _) = rig()
        val bitmap = android.graphics.Bitmap.createBitmap(960, 480, android.graphics.Bitmap.Config.ARGB_8888)
        r.pager.draw(android.graphics.Canvas(bitmap))
        r.pager.dotsVisible = false
        r.pager.draw(android.graphics.Canvas(bitmap))
    }

    // ---- 実際の PanelView を載せる(ボタンの上のスワイプ・タップ) ----

    private val screens = listOf(ScreenInfo("status", "状況"), ScreenInfo("photo", "写真"))

    private inner class Demo {
        var mono = 1_000L
        var epoch = Instant.parse("2026-01-01T03:59:35Z").toEpochMilli()
        val actions = mutableListOf<Action>()
        val sleeps = mutableListOf<Boolean>()
        val controller = PageController(60_000)
        val settled = mutableListOf<Pair<Page, PageReason>>()
        val view = PanelView(ctx, { action, _ -> actions += action }, { error("Settings must not be opened") }, { mono }, { epoch })
        val daidai = DaidaiView(ctx)
        val pager = PagerLayout(ctx, view, daidai, controller, gestureSwallowed = { view.gestureSwallowed }, monotonicMs = { mono })
        private var downTime = 0L

        init {
            controller.addListener(object : PageListener {
                override fun onBVisibleChanged(visible: Boolean) { view.idleSuspended = visible }
                override fun onSettled(page: Page, reason: PageReason) { settled += page to reason }
            })
            pager.measure(MeasureSpec.makeMeasureSpec(960, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(480, MeasureSpec.EXACTLY))
            pager.layout(0, 0, 960, 480)
            view.configured = true
            view.wifi = true
            view.onSleepChanged = { sleeps += it }
            view.sleepTimeoutMs = 180_000
            view.state = PanelState(KioskStatus(1, "DEMO", screens, 0, "OVERVIEW", null, true, 3600,
                DisplayInfo(true, "auto", true), RotationInfo(RotationState.ON, control = true)), mono)
        }

        fun key(id: String) = PanelLayout(960f, 480f).buttons.first { it.id == id }.box

        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(downTime, mono, action, x, y, 0)
            pager.dispatchTouchEvent(e)
            e.recycle()
        }
        fun down(x: Float, y: Float) { downTime = mono; send(MotionEvent.ACTION_DOWN, x, y) }
        fun move(x: Float, y: Float, dt: Long = 16) { advance(dt); send(MotionEvent.ACTION_MOVE, x, y) }
        fun up(x: Float, y: Float, dt: Long = 16) { advance(dt); send(MotionEvent.ACTION_UP, x, y) }
        fun tap(b: Box) { down(b.centerX, b.centerY); up(b.centerX, b.centerY, 30) }
        fun swipeFrom(b: Box, toX: Float) {
            down(b.centerX, b.centerY)
            for (i in 1..8) move(b.centerX + (toX - b.centerX) * i / 8, b.centerY)
            up(toX, b.centerY)
        }

        fun advance(ms: Long) {
            mono += ms
            epoch += ms
            controller.tick(mono)
            pager.stepAnimation()
        }
    }

    @Test fun shortTapStillPressesTheButton() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val d = Demo()
        d.tap(d.key("next"))
        assertEquals(listOf<Action>(Action.Key("next")), d.actions)
        d.tap(d.key("select"))
        assertEquals(listOf<Action>(Action.Key("next"), Action.Key("select")), d.actions)
        assertEquals(Page.A, d.controller.settledPage)
        assertEquals(0f, d.pager.position, 0f)
    }

    @Test fun swipeStartingOnAButtonNeverPressesIt() {
        val d = Demo()
        val b = d.key("next")
        d.swipeFrom(b, 100f)       // ボタンの上から左へ
        d.advance(PagerLayout.ANIMATION_MS)
        assertTrue("ボタンは押されない", d.actions.isEmpty())
        assertEquals(Page.B, d.controller.settledPage)
    }

    @Test fun swipeThatReturnsToAWithoutEnoughMovementStillDoesNotPressAButton() {
        val d = Demo()
        val b = d.key("select")
        d.down(b.centerX, b.centerY)
        d.move(b.centerX - 40, b.centerY, 100)
        d.up(b.centerX, b.centerY, 100)      // 元の場所で離す
        d.advance(PagerLayout.ANIMATION_MS)
        assertTrue(d.actions.isEmpty())
        assertEquals(Page.A, d.controller.settledPage)
    }

    @Test fun clockWakeTouchReturnsToAAndSameGestureNeverMovesThePage() {
        val d = Demo()
        val b = d.key("next")
        d.advance(180_000)              // 時計の満了。描かれていないが、タッチの DOWN で気づく
        d.down(b.centerX, b.centerY)    // 時計を消すだけの最初のタッチ
        assertTrue(d.view.gestureSwallowed)
        for (i in 1..10) d.move(b.centerX - 60f * i, b.centerY)     // その操作の途中で大きく横へ
        assertEquals("ページは動かない", 0f, d.pager.position, 0f)
        d.up(b.centerX - 600f, b.centerY)
        d.advance(PagerLayout.ANIMATION_MS)
        assertEquals(Page.A, d.controller.settledPage)
        assertEquals(0f, d.pager.position, 0f)
        assertTrue("ボタンにも渡らない", d.actions.isEmpty())
        assertEquals(listOf(true, false), d.sleeps)
        assertFalse(d.view.gestureSwallowed)
        // 次の指は普通に動く
        d.swipeFrom(b, 100f)
        d.advance(PagerLayout.ANIMATION_MS)
        assertEquals(Page.B, d.controller.settledPage)
    }

    @Test fun clockWakeTouchThenTapPressesAsUsual() {
        val d = Demo()
        d.advance(180_000)
        d.tap(d.key("next"))
        assertTrue(d.actions.isEmpty())
        assertEquals(listOf(true, false), d.sleeps)
        d.tap(d.key("next"))
        assertEquals(listOf<Action>(Action.Key("next")), d.actions)
    }

    @Test fun idleSuspendedWhileBIsShownAndReleasedWithATouchedTimer() {
        val d = Demo()
        d.swipeFrom(d.key("next"), 100f)
        d.advance(PagerLayout.ANIMATION_MS)
        assertTrue(d.view.idleSuspended)
        // 600 秒たっても(A の 180 秒を超えても)時計は出ない。B 側の戻りを遅らせる
        d.controller.idleMs = 600_000
        d.advance(590_000)
        assertEquals(Page.B, d.controller.settledPage)
        assertTrue(d.view.idleSuspended)
        d.advance(11_000)   // IDLE_RETURN の開始
        d.advance(PagerLayout.ANIMATION_MS)
        assertEquals(listOf(Page.B to PageReason.SWIPE, Page.A to PageReason.IDLE_RETURN), d.settled)
        assertEquals(Page.A, d.controller.settledPage)
        assertFalse(d.view.idleSuspended)
        assertTrue("戻った瞬間に時計は出ない", d.sleeps.isEmpty())
        // 戻った時点から数える: 179 秒では出ない(タッチで確かめると、満了済みなら飲み込まれる)
        d.advance(179_000)
        d.tap(d.key("next"))
        assertEquals(listOf<Action>(Action.Key("next")), d.actions)
        assertTrue(d.sleeps.isEmpty())
        // さらに sleep_minutes 触らなければ時計に入る(次のタッチは飲み込まれる)
        d.advance(180_000)
        d.tap(d.key("select"))
        assertEquals(listOf<Action>(Action.Key("next")), d.actions)
        assertEquals(listOf(true, false), d.sleeps)
    }
}
