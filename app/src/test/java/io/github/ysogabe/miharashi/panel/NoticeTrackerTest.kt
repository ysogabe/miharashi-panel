package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC12 / AC18: 新着通知の検知。 */
class NoticeTrackerTest {
    private fun n(id: String, age: Long = 5, low: Boolean = false) = DaidaiNotice(
        id = id, title = "t-$id", body = "b-$id", ageS = age,
        priority = if (low) NoticePriority.LOW else NoticePriority.NORMAL,
    )

    private fun info(rev: Long?, vararg notices: DaidaiNotice, unseen: Int? = null, ack: Boolean = false) =
        DaidaiInfo(rev, notices.toList(), unseen, ack, null)

    /** 基準(最初の status)を取った tracker。 */
    private fun baselined(): NoticeTracker = NoticeTracker().also {
        assertNull(it.onStatus(info(1, n("old", age = 3)), 0))
    }

    @Test fun firstStatusIsBaselineNeverSwitches() {
        val t = NoticeTracker()
        assertNull("起動前の通知(age 小さくても)は切り替えない", t.onStatus(info(10, n("a", age = 1), n("b", age = 2)), 1_000))
        assertNull("同じ通知の再送", t.onStatus(info(10, n("a", age = 6), n("b", age = 7)), 6_000))
    }

    @Test fun newNoticeAfterBaselineIsCandidate() {
        val t = baselined()
        val c = t.onStatus(info(2, n("new", age = 4), n("old", age = 20)), 5_000)!!
        assertEquals("new", c.notice.id)
        assertEquals(5_000L, c.receivedAtMs)
        assertEquals(2L, c.rev)
        assertEquals(4_000L, c.ageMs(5_000))
        assertEquals(7_000L, c.ageMs(8_000))
    }

    @Test fun duplicateIdSwitchesOnlyOnce() {
        val t = baselined()
        assertEquals("new", t.onStatus(info(2, n("new")), 0)!!.notice.id)
        assertNull("同じ rev(SSE とポーリングの二重)", t.onStatus(info(2, n("new")), 100))
        assertNull("rev が進んでも同じ id", t.onStatus(info(3, n("new", age = 9)), 4_000))
    }

    @Test fun sameRevIsAcceptedButStaleRevIsDroppedWhole() {
        val t = baselined()
        assertNull(t.onStatus(info(5, n("old")), 0))
        val stale = info(4, n("late", age = 1), unseen = 9, ack = true)
        assertNull("古い rev の後着は候補にならない", t.onStatus(stale, 10))
        assertEquals("表示用データも更新しない", "old", t.latestNotice?.id)
        assertEquals(5L, t.rev)
        // 捨てた通知は既知にもならない(もっと新しい rev で再び現れたら通常どおり扱う)
        assertEquals("late", t.onStatus(info(6, n("late", age = 2)), 20)!!.notice.id)
    }

    @Test fun sameRevWithNewIdIsAccepted() {
        val t = baselined()
        assertEquals("x", t.onStatus(info(1, n("x"), n("old")), 0)!!.notice.id)
    }

    @Test fun tooOldAgeIsSkippedAndKnown() {
        val t = baselined()
        assertNull(t.onStatus(info(2, n("slow", age = NoticeTracker.AUTO_SWITCH_MAX_AGE_S + 1)), 0))
        assertNull("見送った通知は既知になり、あとで同じ通知で切り替わらない", t.onStatus(info(3, n("slow", age = 1)), 0))
        assertEquals("ちょうど上限は対象", "edge", t.onStatus(info(4, n("edge", age = 30)), 0)!!.notice.id)
    }

    @Test fun lowPriorityIsSkippedAndKnown() {
        val t = baselined()
        assertNull(t.onStatus(info(2, n("quiet", low = true)), 0))
        assertNull(t.onStatus(info(3, n("quiet", low = false)), 0))
    }

    @Test fun multipleNewPicksLatestNormalFresh() {
        val t = baselined()
        val c = t.onStatus(info(2, n("lowest", low = true), n("mid", age = 8), n("older", age = 12), n("old")), 0)!!
        assertEquals("low は飛ばして、新しい順で最初のもの", "mid", c.notice.id)
        // 候補にならなかった他の新着も既知: 次の status で同じ id は出ない
        assertNull(t.onStatus(info(3, n("older", age = 13), n("old")), 0))
    }

    @Test fun rebaselineForgetsKnownAndSkipsNextStatus() {
        val t = baselined()
        assertEquals("a", t.onStatus(info(2, n("a")), 0)!!.notice.id)
        t.rebaseline() // onResume
        assertEquals(0, t.knownCount)
        assertNull(t.rev)
        assertNull("背面中に来た通知は、復帰後の最初の status では切り替えない", t.onStatus(info(3, n("a", age = 20), n("b", age = 2)), 0))
        assertEquals("c", t.onStatus(info(4, n("c")), 0)!!.notice.id)
    }

    @Test fun rebaselineAcceptsLowerRevFromNewHost() {
        val t = baselined()
        t.onStatus(info(100, n("x")), 0)
        t.rebaseline() // 接続先の変更。別ホストで rev が小さい
        assertNull(t.onStatus(info(3, n("y")), 0))
        assertEquals("z", t.onStatus(info(4, n("z")), 0)!!.notice.id)
    }

    @Test fun missingRevNeverSwitchesButUpdatesDisplay() {
        val t = NoticeTracker()
        assertNull(t.onStatus(info(null, n("a")), 0))
        assertNull(t.onStatus(info(null, n("b"), n("a"), unseen = 2, ack = true), 0))
        assertEquals("b", t.latestNotice?.id)
        assertEquals(2, t.notices.size)
        assertEquals(2, t.unseen)
        assertTrue(t.ack)
    }

    @Test fun missingRevIdsAreKnownIfHostLaterGainsRev() {
        val t = NoticeTracker()
        t.onStatus(info(null, n("a")), 0)
        assertNull(t.onStatus(info(1, n("a")), 0))
        assertEquals("b", t.onStatus(info(2, n("b"), n("a")), 0)!!.notice.id)
    }

    @Test fun nullDaidaiDoesNothingAndDoesNotTakeBaseline() {
        val t = NoticeTracker()
        assertNull(t.onStatus(null, 0))
        assertNull(t.rev)
        assertTrue(t.notices.isEmpty())
        assertNull("null の status は基準にならない: 最初の daidai ありが基準", t.onStatus(info(1, n("a", age = 1)), 10))
        assertNull(t.onStatus(null, 20))
        assertEquals("表示用データはそのまま", "a", t.latestNotice?.id)
        assertEquals("b", t.onStatus(info(2, n("b")), 30)!!.notice.id)
    }

    @Test fun emptyNoticesDoNothing() {
        val t = baselined()
        assertNull(t.onStatus(info(2), 0))
        assertTrue(t.notices.isEmpty())
        assertEquals(2L, t.rev)
        assertEquals("x", t.onStatus(info(3, n("x")), 0)!!.notice.id)
    }

    @Test fun firstEmptySnapshotStillBaselines() {
        val t = NoticeTracker()
        assertNull(t.onStatus(info(1), 0))
        assertEquals("起動後の最初の status が空でも、基準は取れている", "a", t.onStatus(info(2, n("a")), 0)!!.notice.id)
    }

    @Test fun knownSetIsBoundedAndDropsOldestFirst() {
        val t = baselined() // "old"
        for (i in 1..NoticeTracker.KNOWN_IDS_MAX + 10) assertNull("低優先度で既知に積む", t.onStatus(info(1L + i, n("k$i", low = true)), 0))
        assertEquals(NoticeTracker.KNOWN_IDS_MAX, t.knownCount)
        val rev = 1000L
        // 最近の id は覚えている
        assertNull(t.onStatus(info(rev, n("k${NoticeTracker.KNOWN_IDS_MAX + 10}")), 0))
        // 最古の "old" は忘れている
        assertEquals("old", t.onStatus(info(rev + 1, n("old", age = 1)), 0)!!.notice.id)
    }

    @Test fun displayAccessorsFollowLatestSnapshot() {
        val t = NoticeTracker()
        t.onStatus(DaidaiInfo(1, listOf(n("a")), unseen = 3, ack = true, avatarPose = "talking"), 0)
        assertEquals(3, t.unseen)
        assertEquals("talking", t.avatarPose)
        t.onStatus(DaidaiInfo(2, listOf(n("a")), unseen = 3, ack = false, avatarPose = null), 0)
        assertNull("ack でなければ unseen は採用しない", t.unseen)
        assertFalse(t.ack)
    }

    @Test fun rebaselineKeepsDisplayData() {
        val t = baselined()
        t.rebaseline()
        assertEquals("old", t.latestNotice?.id)
    }

    @Test fun hugeAgeDoesNotOverflow() {
        val t = baselined()
        assertNull(t.onStatus(info(2, n("huge", age = Long.MAX_VALUE)), 0))
        val c = NoticeCandidate(n("huge", age = Long.MAX_VALUE), 0, 2)
        assertTrue(c.ageMs(0) > NoticeTracker.AUTO_SWITCH_MAX_AGE_S * 1000)
    }

    @Test fun toStringsDoNotLeakBody() {
        val c = NoticeCandidate(n("secret"), 0, 1)
        assertFalse(c.toString().contains("b-secret"))
        assertFalse(c.toString().contains("t-secret"))
    }

    // --- 未読(R17、AC16) ---

    @Test fun localUnreadCountsNoticesNotYetSeenAndSurvivesRebaseline() {
        val t = NoticeTracker()
        assertEquals("daidai を受け取る前は 0", 0, t.unreadCount)
        t.onStatus(info(1, n("a"), n("b")), 0)
        assertEquals("ホストが既読を管理しない(ack=false)ので Panel が数える", 2, t.unreadCount)
        t.markAllSeen()
        assertEquals(0, t.unreadCount)
        t.onStatus(info(2, n("c"), n("a"), n("b")), 1_000)
        assertEquals("新しい通知が来たら、また未読になる", 1, t.unreadCount)
        t.markAllSeen()
        t.rebaseline()
        assertEquals("基準の取り直し(onResume)では既読を捨てない", 0, t.unreadCount)
        t.onStatus(info(3, n("c"), n("a"), n("b")), 2_000)
        assertEquals(0, t.unreadCount)
    }

    @Test fun hostManagedUnseenIsUsedOnlyWhenAckIsTrue() {
        val t = NoticeTracker()
        t.onStatus(info(1, n("a"), n("b"), n("c"), unseen = 7, ack = true), 0)
        assertEquals("ack=true はホストの unseen(5 件を超えてもそのまま)", 7, t.unreadCount)
        t.markAllSeen() // ホストが管理するときはローカルの既読で件数を変えない(ホストの次の status を待つ)
        assertEquals(7, t.unreadCount)
        t.onStatus(info(2, n("a"), n("b"), n("c"), unseen = 0, ack = true), 1_000)
        assertEquals(0, t.unreadCount)

        val local = NoticeTracker()
        local.onStatus(info(1, n("a"), unseen = 9, ack = false), 0)
        assertEquals("ack=false の unseen は採用しない", 1, local.unreadCount)
    }

    @Test fun ackTrueWithUnreadableUnseenFallsBackToLocalCount() {
        val t = NoticeTracker()
        t.onStatus(info(1, n("a"), n("b"), unseen = null, ack = true), 0)
        assertEquals(2, t.unreadCount)
        t.markAllSeen()
        assertEquals(0, t.unreadCount)
    }

    @Test fun seenSetIsBounded() {
        val t = NoticeTracker()
        for (i in 0 until 40) {
            t.onStatus(info(i.toLong(), n("x$i-a"), n("x$i-b")), i * 10L)
            t.markAllSeen()
        }
        assertEquals(0, t.unreadCount)
    }

    @Test fun arrivalsAreNewIdsInTheWindowEvenWhenTheCountStaysTheSame() {
        val t = NoticeTracker()
        t.onStatus(info(1, n("e"), n("d"), n("c"), n("b"), n("a")), 0)
        assertEquals("最初の status は全部が窓に現れた id", listOf("e", "d", "c", "b", "a"), t.lastArrivals)
        t.markAllSeen()
        assertEquals(emptyList<String>(), t.unreadIds)
        // 窓が入れ替わる: 5 件のまま、a が落ちて f が現れる
        t.onStatus(info(2, n("f"), n("e"), n("d"), n("c"), n("b")), 1_000)
        assertEquals(listOf("f"), t.lastArrivals)
        assertEquals(listOf("f", "e", "d", "c", "b"), t.windowIds)
        assertEquals("既読にした id は未読 id に入らない", listOf("f"), t.unreadIds)
        assertEquals(1, t.unreadCount)
        // 同じ窓の再配信は新着なし
        t.onStatus(info(2, n("f"), n("e"), n("d"), n("c"), n("b")), 2_000)
        assertEquals(emptyList<String>(), t.lastArrivals)
    }

    @Test fun staleSnapshotAndNullDaidaiHaveNoArrivals() {
        val t = NoticeTracker()
        t.onStatus(info(5, n("a")), 0)
        t.onStatus(info(6, n("b"), n("a")), 1_000)
        assertEquals(listOf("b"), t.lastArrivals)
        t.onStatus(info(4, n("z"), n("a")), 2_000) // 後着の古いスナップショットは捨てる
        assertEquals(emptyList<String>(), t.lastArrivals)
        assertEquals(listOf("b", "a"), t.windowIds)
        t.onStatus(info(7, n("c"), n("b"), n("a")), 3_000)
        t.onStatus(null, 4_000)
        assertEquals(emptyList<String>(), t.lastArrivals)
    }

    @Test fun arrivalsForAckHostFollowIdsNotUnseen() {
        val t = NoticeTracker()
        t.onStatus(info(1, n("c"), n("b"), n("a"), unseen = 3, ack = true), 0)
        t.onStatus(info(2, n("d"), n("c"), n("b"), unseen = 3, ack = true), 1_000) // unseen は同数のまま入れ替わる
        assertEquals(3, t.unreadCount)
        assertEquals(listOf("d"), t.lastArrivals)
    }
}
