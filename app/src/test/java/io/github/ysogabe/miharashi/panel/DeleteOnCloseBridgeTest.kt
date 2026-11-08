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
    }

    private val req = SessionRequest("echo-show-5", "0.3.0", StartReason.SWIPE, null)

    @Test fun aCreateThatSucceedsAfterCloseIsDeletedOnTheCallingThread() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        assertTrue(b.close().isEmpty()) // 通信中に close された(未取消の ID はまだない)
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
        assertEquals("close が未取消の ID を返す(呼び出し側がワーカーで cancel する)", listOf("s-1"), b.close())
        assertTrue("close 自体は通信しない(メインスレッドから呼ばれる)", inner.cancels.isEmpty())
        assertTrue("2 回目は空(冪等)", b.close().isEmpty())
    }

    @Test fun anIdTheSessionAlreadyCancelledIsNotCollectedByClose() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        b.cancel("s-1") // VoiceSession が通常の取消で消した
        assertTrue(b.close().isEmpty())
        assertEquals(listOf("s-1"), inner.cancels)
    }

    @Test fun deletingAgainAfterCloseCollectedItIsHarmlessAndIdempotent() {
        // close が回収した ID を、セッション側の取消も消しに来る(二重の DELETE)。どちらも例外を出さず、ホストは冪等に受ける
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner)
        b.createSession(req)
        val orphans = b.close()
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
        assertTrue(b2.close().isEmpty())
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
        assertTrue(b.close().isEmpty())
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
        val orphans = b.close() // ワーカーはまだ createSession の中: 未取消の ID はなく、閉じた印だけが立つ
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
        assertEquals("登録済みの ID を close が回収する", listOf("s-1"), b.close())
        worker.join(10_000)
    }
}
