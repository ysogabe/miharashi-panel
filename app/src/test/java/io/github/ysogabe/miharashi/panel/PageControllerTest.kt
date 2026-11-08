package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

class PageControllerTest {
    private class Recorder : PageListener {
        val settled = mutableListOf<Pair<Page, PageReason>>()
        val visible = mutableListOf<Boolean>()
        val moves = mutableListOf<Pair<Page, PageReason>>()
        val snaps = mutableListOf<Page>()
        override fun onSettled(page: Page, reason: PageReason) { settled += page to reason }
        override fun onBVisibleChanged(visible: Boolean) { this.visible += visible }
        override fun onMoveRequested(target: Page, reason: PageReason) { moves += target to reason }
        override fun onSnap(page: Page) { snaps += page }
    }

    private val c = PageController(idleMs = 60_000)
    private val r = Recorder().also { c.addListener(it) }

    private fun swipeTo(page: Page, now: Long) {
        assertTrue(c.beginDrag(now))
        c.endDrag(page, now)
        c.animationEnded(now + 180)
    }

    @Test fun startsAtAAndIdle() {
        assertEquals(Page.A, c.settledPage)
        assertEquals(Page.A, c.targetPage)
        assertEquals(PageController.Phase.IDLE, c.phase)
        assertFalse(c.userBusy)
        assertFalse(c.bVisible)
        assertFalse(c.sessionActive)
    }

    @Test fun swipeSettlesExactlyOnceAndNotWhileDragging() {
        assertTrue(c.beginDrag(0))
        // ドラッグ途中で B が半分見えている間は、入場(settled)を出さない
        assertTrue(r.settled.isEmpty())
        assertTrue(c.userBusy)
        assertTrue(c.bVisible)
        assertEquals(Page.A, c.settledPage)
        c.endDrag(Page.B, 100)
        assertTrue("アニメーション中も入場ではない", r.settled.isEmpty())
        assertEquals(listOf(Page.B to PageReason.SWIPE), r.moves)
        assertEquals(Page.A, c.settledPage)
        assertEquals(Page.B, c.targetPage)
        c.animationEnded(280)
        assertEquals(listOf(Page.B to PageReason.SWIPE), r.settled)
        assertEquals(Page.B, c.settledPage)
        // 二重に呼ばれても一度だけ
        c.animationEnded(300)
        c.animationEnded(320)
        assertEquals(1, r.settled.size)
        assertFalse(c.userBusy)
    }

    @Test fun snapBackToSamePageEmitsNothing() {
        assertTrue(c.beginDrag(0))
        c.endDrag(Page.A, 50)
        c.animationEnded(230)
        assertTrue(r.settled.isEmpty())
        assertEquals(Page.A, c.settledPage)
        assertFalse(c.bVisible)
        // B で途中までドラッグして戻す: 入場の再通知なし
        swipeTo(Page.B, 1000)
        assertEquals(1, r.settled.size)
        assertTrue(c.beginDrag(2000))
        c.endDrag(Page.B, 2100)
        c.animationEnded(2280)
        assertEquals(1, r.settled.size)
        assertTrue(c.bVisible)
    }

    @Test fun swipeBackToAReportsLeavingB() {
        swipeTo(Page.B, 0)
        swipeTo(Page.A, 1000)
        assertEquals(listOf(Page.B to PageReason.SWIPE, Page.A to PageReason.SWIPE), r.settled)
        assertEquals(listOf(true, false), r.visible)
    }

    @Test fun bVisibleChangesOnlyWhenItActuallyChanges() {
        c.beginDrag(0)
        c.endDrag(Page.B, 10)
        c.animationEnded(190)
        assertEquals(listOf(true), r.visible)
        swipeTo(Page.A, 1000)
        assertEquals(listOf(true, false), r.visible)
    }

    @Test fun cannotBeginDragWhileAnimating() {
        c.beginDrag(0)
        c.endDrag(Page.B, 10)
        assertFalse(c.beginDrag(20))
        c.animationEnded(190)
        assertTrue(c.beginDrag(200))
    }

    @Test fun goToAnimatesAndSettlesOnceWithItsReason() {
        assertTrue(c.goTo(Page.B, PageReason.AUTO_NOTICE, 0))
        assertEquals(listOf(Page.B to PageReason.AUTO_NOTICE), r.moves)
        assertTrue(c.userBusy)
        assertTrue(r.settled.isEmpty())
        // 同じ行き先への重ねての要求は、そのまま進行中として受け付ける(二重に動かさない)
        assertTrue(c.goTo(Page.B, PageReason.AUTO_NOTICE, 50))
        assertEquals(1, r.moves.size)
        // 別の行き先は受け付けない
        assertFalse(c.goTo(Page.A, PageReason.IDLE_RETURN, 60))
        c.animationEnded(180)
        assertEquals(listOf(Page.B to PageReason.AUTO_NOTICE), r.settled)
    }

    @Test fun goToIsRefusedWhileDraggingOrAtTheSamePage() {
        assertFalse("A にいて A へ", c.goTo(Page.A, PageReason.AUTO_NOTICE, 0))
        c.beginDrag(0)
        assertTrue(c.userBusy)
        assertFalse(c.goTo(Page.B, PageReason.AUTO_NOTICE, 10))
        assertTrue(r.moves.isEmpty())
        c.endDrag(Page.A, 20)
        assertFalse("ユーザーの戻りアニメーション中", c.goTo(Page.B, PageReason.AUTO_NOTICE, 30))
        c.animationEnded(200)
        assertFalse(c.userBusy)
        assertTrue("落ち着いたあとは受け付ける", c.goTo(Page.B, PageReason.AUTO_NOTICE, 210))
    }

    @Test fun idleReturnAfterDaidaiIdleSecondsOfNoTouch() {
        swipeTo(Page.B, 0)
        assertFalse(c.tick(59_000))
        assertEquals(Page.B, c.settledPage)
        // 触ると数え直す
        c.touch(59_500)
        assertFalse(c.tick(119_000))
        assertFalse(c.tick(119_499))
        assertTrue(c.tick(119_500))
        assertEquals(Page.A to PageReason.IDLE_RETURN, r.moves.last())
        assertEquals(Page.B, c.settledPage)
        c.animationEnded(119_680)
        assertEquals(Page.A to PageReason.IDLE_RETURN, r.settled.last())
        assertEquals(Page.A, c.settledPage)
        assertFalse(c.bVisible)
    }

    @Test fun idleTimerStartsFromSettleNotFromDrag() {
        // 入場してから数える(ドラッグ中の 59 秒は含めない)
        assertTrue(c.beginDrag(0))
        c.endDrag(Page.B, 0)
        c.animationEnded(180)
        assertFalse(c.tick(60_000))
        assertTrue(c.tick(60_180))
    }

    @Test fun noIdleReturnOnAOrWhileUserBusyOrWhenDisabled() {
        assertFalse(c.tick(10_000_000))
        swipeTo(Page.B, 0)
        c.beginDrag(100_000)
        assertFalse("ドラッグ中は戻さない", c.tick(10_000_000))
        c.endDrag(Page.B, 100_000)
        assertFalse("アニメーション中も戻さない", c.tick(10_000_000))
        c.animationEnded(100_180)
        c.idleMs = 0
        assertFalse("0 は無効", c.tick(10_000_000))
        assertEquals(Page.B, c.settledPage)
    }

    @Test fun sessionActiveStopsTheIdleTimerAndResumesCountingFromTheEnd() {
        swipeTo(Page.B, 0)
        c.sessionActive = true
        // 進行中は何分たっても戻らない
        for (t in 1..20) assertFalse(c.tick(t * 60_000L))
        assertEquals(Page.B, c.settledPage)
        c.sessionActive = false
        // 終わったあとの最初の tick から数え直す(古い時刻のまま即戻らない)
        assertFalse(c.tick(1_300_000))
        assertFalse(c.tick(1_300_000 + 59_999))
        assertTrue(c.tick(1_300_000 + 60_000))
    }

    @Test fun sessionActiveFlagWithoutTicksStillRearmsFromNextTick() {
        swipeTo(Page.B, 0)
        c.sessionActive = true
        c.sessionActive = false
        // tick が来ないまま長く経っても、終了直後に戻ってしまわない
        assertFalse(c.tick(500_000))
        assertTrue(c.tick(560_000))
    }

    @Test fun pauseReturnsToAAndSnapsWithoutAnimation() {
        swipeTo(Page.B, 0)
        c.onPause(1000)
        assertEquals(Page.A, c.settledPage)
        assertEquals(PageController.Phase.IDLE, c.phase)
        assertEquals(listOf(Page.A), r.snaps)
        assertEquals(Page.A to PageReason.RESUME, r.settled.last())
        assertEquals(listOf(true, false), r.visible)
        // 戻るときの追加の通知はない
        c.onResume(2000)
        assertEquals(2, r.settled.size)
        assertEquals(Page.A, c.settledPage)
    }

    @Test fun pauseDuringDragOrAnimationDropsItAndLateAnimationEndIsIgnored() {
        c.beginDrag(0)
        c.endDrag(Page.B, 10)
        c.onPause(50)
        assertEquals(Page.A, c.settledPage)
        c.animationEnded(190)
        assertTrue("入場していないので何も出さない", r.settled.isEmpty())
        assertEquals(Page.A, c.settledPage)
        assertFalse(c.bVisible)
        c.beginDrag(300)
        c.onPause(310)
        assertEquals(PageController.Phase.IDLE, c.phase)
        assertTrue(r.settled.isEmpty())
    }

    @Test fun resumeAlwaysStartsFromAAndCountsIdleFromThere() {
        swipeTo(Page.B, 0)
        c.onResume(5_000)
        assertEquals(Page.A, c.settledPage)
        assertEquals(Page.A to PageReason.RESUME, r.settled.last())
        // 再度 B に入れば、そこから数える
        swipeTo(Page.B, 10_000)
        assertFalse(c.tick(10_000 + 180 + 59_999))
        assertTrue(c.tick(10_000 + 180 + 60_000))
    }

    @Test fun newControllerNeverStartsOnB() {
        assertEquals(Page.A, PageController().settledPage)
        assertEquals(60_000L, PageController.DEFAULT_IDLE_MS)
    }

    @Test fun listenerCanReactToSettleByStartingAnotherMove() {
        val chained = PageController(60_000)
        chained.addListener(object : PageListener {
            override fun onSettled(page: Page, reason: PageReason) {
                // 落ち着いたあとの再評価(T5)は、落ち着いた状態から行える
                if (page == Page.B) assertTrue(chained.goTo(Page.A, PageReason.IDLE_RETURN, 0))
            }
        })
        chained.goTo(Page.B, PageReason.AUTO_NOTICE, 0)
        chained.animationEnded(180)
        assertEquals(Page.A, chained.targetPage)
        assertEquals(Page.B, chained.settledPage)
    }
}
