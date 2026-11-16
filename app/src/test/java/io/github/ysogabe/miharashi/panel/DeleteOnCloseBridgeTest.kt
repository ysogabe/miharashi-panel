package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeleteOnCloseBridgeTest {
    private class Inner(var id: String = "s-1", var cancelThrows: Boolean = false, var createThrows: Boolean = false) : BridgeApi {
        val cancels = mutableListOf<String>()
        var creates = 0
        var closedAtCreate: (() -> Boolean)? = null
        val order = mutableListOf<String>()
        override fun createSession(req: SessionRequest): SessionInfo {
            creates++
            order += "create"
            if (createThrows) throw BridgeException(BridgeFailure.NETWORK, 0, null, null)
            return SessionInfo(id)
        }

        override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean) = ChunkAck(AckState.LISTENING, seq).also { order += "send" }
        override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) { order += "events" }
        override fun fetchReplyAudio(id: String, index: Int) = ByteArray(3).also { order += "fetch" }
        override fun cancel(id: String) {
            order += "cancel"
            cancels += id
            if (cancelThrows) throw java.io.IOException("down")
        }

        override fun abortInFlight() { order += "abort" }

        val conversationEnds = mutableListOf<String>()
        override fun endConversation(handle: String) {
            order += "endConversation"
            conversationEnds += handle
        }
    }

    private val req = SessionRequest("echo-show-5", "0.3.0", StartReason.SWIPE, null)

    @Test fun aCreateThatSucceedsAfterCloseIsDeletedOnTheCallingThread() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        assertTrue(b.close().sessionIds.isEmpty()) // 通信中に close された(未取消の ID はまだない)
        val info = b.createSession(req)
        assertEquals("結果はそのまま返す(配送されれば VoiceSession も消す。DELETE は冪等)", "s-1", info.sessionId)
        assertEquals(listOf("s-1"), inner.cancels)
        assertEquals(listOf("create", "cancel"), inner.order)
    }

    @Test fun aCreateBeforeCloseIsNotDeletedUntilCloseCollectsIt() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        assertTrue("閉じる前は消さない", inner.cancels.isEmpty())
        assertEquals("close が未取消の ID を返す(呼び出し側がワーカーで cancel する)", listOf("s-1"), b.close().sessionIds)
        assertTrue("close 自体は通信しない(メインスレッドから呼ばれる)", inner.cancels.isEmpty())
        assertTrue("2 回目は空(冪等)", b.close().sessionIds.isEmpty())
    }

    @Test fun anIdTheSessionAlreadyCancelledIsNotCollectedByClose() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        b.cancel("s-1") // VoiceSession が通常の取消で消した
        assertTrue(b.close().sessionIds.isEmpty())
        assertEquals(listOf("s-1"), inner.cancels)
    }

    @Test fun deletingAgainAfterCloseCollectedItIsHarmlessAndIdempotent() {
        // close が回収した ID を、セッション側の取消も消しに来る(二重の DELETE)。どちらも例外を出さず、ホストは冪等に受ける
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        val orphans = b.close().sessionIds
        orphans.forEach { b.cancel(it) }
        b.cancel("s-1")
        assertEquals(listOf("s-1", "s-1"), inner.cancels)
    }

    @Test fun aMalformedSessionIdIsNeverUsedInAPathNorRegistered() {
        val inner = Inner(id = "../x?y")
        val b = DeleteOnCloseBridge(inner)
        b.close()
        b.createSession(req)
        assertTrue(inner.cancels.isEmpty())
        val inner2 = Inner(id = "../x?y")
        val b2 = DeleteOnCloseBridge(inner2)
        b2.createSession(req)
        assertTrue(b2.close().sessionIds.isEmpty())
    }

    @Test fun aFailingDeleteDoesNotFailTheCreate() {
        val inner = Inner(cancelThrows = true)
        val b = DeleteOnCloseBridge(inner)
        b.close()
        assertEquals("s-1", b.createSession(req).sessionId)
        assertEquals(1, inner.cancels.size)
    }

    @Test fun aFailedCreateIsNotDeletedNorRegisteredAndKeepsItsException() {
        val inner = Inner(createThrows = true)
        val b = DeleteOnCloseBridge(inner)
        try {
            b.createSession(req)
            fail()
        } catch (e: BridgeException) {
            assertEquals(BridgeFailure.NETWORK, e.kind)
        }
        assertTrue(inner.cancels.isEmpty())
        assertTrue(b.close().sessionIds.isEmpty())
    }

    @Test fun everythingElseIsPassedThrough() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.sendAudio("s-1", 0, ByteArray(4), false)
        b.events("s-1", null) { }
        b.fetchReplyAudio("s-1", 0)
        b.cancel("s-1")
        b.abortInFlight()
        assertEquals(listOf("send", "events", "fetch", "cancel", "abort"), inner.order)
    }

    /**
     * レビュー指摘の隙間: 作成が成功して戻った直後(結果を配送する前)に close が完了しても、作成された ID は必ず DELETE される。
     * 内側の createSession の「戻る直前」と、close の完了の順序をラッチで固定する(従来は isClosed が false のまま返して DELETE が漏れた)。
     */
    @Test fun aCloseThatCompletesBetweenTheCreateAndTheDeliveryStillDeletesTheId() {
        val created = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val inner = object : BridgeApi by Inner() {
            val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
            override fun createSession(req: SessionRequest): SessionInfo {
                created.countDown() // ホストは作成した。結果が呼び出し元に届く前に close を完了させる
                assertTrue(closeDone.await(10, TimeUnit.SECONDS))
                return SessionInfo("s-1")
            }

            override fun cancel(id: String) { deleted += id }
        }
        val b = DeleteOnCloseBridge(inner)
        val worker = Thread { b.createSession(req) }.apply { start() }
        assertTrue(created.await(10, TimeUnit.SECONDS))
        val orphans = b.close().sessionIds // ワーカーはまだ createSession の中: 未取消の ID はなく、閉じた印だけが立つ
        assertTrue(orphans.isEmpty())
        closeDone.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals("閉じたあとに成功した作成は、ワーカーがその場で DELETE する", listOf("s-1"), inner.deleted)
    }

    /** もう一方の順序: 作成の登録が先なら、close が回収する(ワーカーがロックを出た直後・配送の前に close が完了する) */
    @Test fun aCloseThatCompletesRightAfterTheRegistrationCollectsTheId() {
        val registeredDone = CountDownLatch(1)
        val inner = Inner()
        val b = DeleteOnCloseBridge(object : BridgeApi by inner {
            override fun createSession(req: SessionRequest) = inner.createSession(req)
        })
        val worker = Thread {
            b.createSession(req)
            registeredDone.countDown() // 結果の配送の直前
        }.apply { start() }
        assertTrue(registeredDone.await(10, TimeUnit.SECONDS))
        assertEquals("登録済みの ID を close が回収する", listOf("s-1"), b.close().sessionIds)
        worker.join(10_000)
    }

    // ---- 会話のハンドル(段階 2a-P)

    private val h1 = "Yp0vAbCdEfGhIjKlMnOp-_"
    private val h2 = "Zq1wBcDeFgHiJkLmNoPq-_"

    /** 作成が成功して 201 に会話のハンドルを載せる内側 */
    private class ConvInner(var handle: String? = "Yp0vAbCdEfGhIjKlMnOp-_", var id: String = "s-1") : BridgeApi {
        val cancels = mutableListOf<String>()
        val ends = mutableListOf<String>()
        var endThrows = false
        override fun createSession(req: SessionRequest) = SessionInfo(id, conversation = ConversationInfo(handle, true, true))
        override fun sendAudio(id: String, seq: Int, pcm: ByteArray, final: Boolean) = ChunkAck(AckState.LISTENING, seq)
        override fun events(id: String, lastEventId: Int?, onEvent: (BridgeEvent) -> Unit) {}
        override fun fetchReplyAudio(id: String, index: Int) = ByteArray(0)
        override fun cancel(id: String) { cancels += id }
        override fun endConversation(handle: String) {
            ends += handle
            if (endThrows) throw java.io.IOException("down")
        }

        override fun abortInFlight() {}
    }

    @Test fun theSameHandleIsSentToTheHostOnlyOnceNoMatterHowManyPathsEndIt() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        b.endConversation(h1) // メインからの会話の終わり
        b.endConversation(h1) // VoiceSession の古い 201 の後始末
        b.endConversation(h1) // close の回収
        assertEquals(listOf(h1), inner.ends)
        b.endConversation(h2)
        assertEquals(listOf(h1, h2), inner.ends)
    }

    @Test fun theRecentEndedSetIsBoundedAndForgetsTheOldest() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        val handles = (0 until 17).map { "H%021d".format(it) } // 22 文字
        handles.forEach { b.endConversation(it) }
        assertEquals(17, inner.ends.size)
        b.endConversation(handles.last()) // 最近のものはまだ覚えている
        assertEquals(17, inner.ends.size)
        b.endConversation(handles.first()) // 17 件目で押し出された最古のものは、もう一度送る
        assertEquals(18, inner.ends.size)
    }

    @Test fun aMalformedHandleIsPassedToTheInnerWhichRejectsItAndIsNotRecorded() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        b.endConversation("bad")
        b.endConversation("bad")
        assertEquals("形の検査は内側の仕事。重複の記録はしない", listOf("bad", "bad"), inner.ends)
    }

    @Test fun aFailingEndIsNotRetriedButDoesNotBreakTheCaller() {
        val inner = ConvInner().also { it.endThrows = true }
        val b = DeleteOnCloseBridge(inner)
        try {
            b.endConversation(h1)
            fail()
        } catch (_: java.io.IOException) {
        }
        b.endConversation(h1)
        assertEquals("記録したあとで内側へ渡す(最良努力。同じハンドルは 1 回だけ試す)", listOf(h1), inner.ends)
    }

    @Test fun aCreateThatSucceedsAfterCloseDeletesAndEndsTheConversationOnce() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        b.close()
        val info = b.createSession(req)
        assertEquals("s-1", info.sessionId)
        assertEquals(h1, info.conversation?.handle)
        assertEquals(listOf("s-1"), inner.cancels)
        assertEquals(listOf(h1), inner.ends)
        // VoiceSession の古い 201 の後始末が同じハンドルを送っても、ホストへは 1 回
        b.endConversation(h1)
        b.cancel("s-1")
        assertEquals(listOf(h1), inner.ends)
    }

    @Test fun aCreateAfterCloseWithoutAHandleSendsNoConversationEnd() {
        val inner = ConvInner(handle = null)
        val b = DeleteOnCloseBridge(inner)
        b.close()
        b.createSession(req)
        assertEquals(listOf("s-1"), inner.cancels)
        assertTrue(inner.ends.isEmpty())
    }

    @Test fun aFailingEndAfterCloseDoesNotFailTheCreate() {
        val inner = ConvInner().also { it.endThrows = true }
        val b = DeleteOnCloseBridge(inner)
        b.close()
        assertEquals("s-1", b.createSession(req).sessionId)
        assertEquals(listOf(h1), inner.ends)
    }

    @Test fun closeCollectsTheHandleOfAnUncancelledCreateTogetherWithItsId() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        assertTrue("閉じる前は何も送らない", inner.ends.isEmpty() && inner.cancels.isEmpty())
        val orphans = b.close()
        assertEquals(listOf("s-1"), orphans.sessionIds)
        assertEquals(listOf(h1), orphans.handles)
        assertTrue("close 自体は通信しない(メインスレッドから呼ばれる)", inner.ends.isEmpty() && inner.cancels.isEmpty())
        assertFalse("toString にハンドルも ID も出さない", orphans.toString().contains(h1) || orphans.toString().contains("s-1"))
        // 呼び出し側(AndroidVoiceRuntime)がワーカーで送る。持ち主(会話)がすでに送っていても 1 回
        b.endConversation(h1)
        orphans.handles.forEach { b.endConversation(it) }
        assertEquals(listOf(h1), inner.ends)
        assertTrue("2 回目の close は空", b.close().let { it.sessionIds.isEmpty() && it.handles.isEmpty() })
    }

    @Test fun anUncancelledCreateWithoutAHandleCollectsOnlyTheId() {
        val inner = ConvInner(handle = null)
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        val orphans = b.close()
        assertEquals(listOf("s-1"), orphans.sessionIds)
        assertTrue(orphans.handles.isEmpty())
    }

    @Test fun aCancelledSessionIsNotCollectedWithItsHandle() {
        val inner = ConvInner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        b.cancel("s-1") // 通常の取消。ハンドルは持ち主(会話)の責任に移っている
        val orphans = b.close()
        assertTrue(orphans.sessionIds.isEmpty())
        assertTrue(orphans.handles.isEmpty())
    }
}
