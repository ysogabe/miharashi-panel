package io.github.ysogabe.miharashi.panel

import org.junit.Assert.*
import org.junit.Test

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
        var closed = false
        val b = DeleteOnCloseBridge(inner) { closed }
        closed = true // 通信中に close された
        val info = b.createSession(req)
        assertEquals("結果はそのまま返す(配送されれば VoiceSession も消す。DELETE は冪等)", "s-1", info.sessionId)
        assertEquals(listOf("s-1"), inner.cancels)
        assertEquals(listOf("create", "cancel"), inner.order)
    }

    @Test fun aCreateBeforeCloseIsNotDeleted() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner) { false }
        b.createSession(req)
        assertTrue(inner.cancels.isEmpty())
    }

    @Test fun aMalformedSessionIdIsNeverUsedInAPath() {
        val inner = Inner(id = "../x?y")
        val b = DeleteOnCloseBridge(inner) { true }
        b.createSession(req)
        assertTrue(inner.cancels.isEmpty())
    }

    @Test fun aFailingDeleteDoesNotFailTheCreate() {
        val inner = Inner(cancelThrows = true)
        val b = DeleteOnCloseBridge(inner) { true }
        assertEquals("s-1", b.createSession(req).sessionId)
        assertEquals(1, inner.cancels.size)
    }

    @Test fun aFailedCreateIsNotDeletedAndKeepsItsException() {
        val inner = Inner(createThrows = true)
        val b = DeleteOnCloseBridge(inner) { true }
        try {
            b.createSession(req)
            fail()
        } catch (e: BridgeException) {
            assertEquals(BridgeFailure.NETWORK, e.kind)
        }
        assertTrue(inner.cancels.isEmpty())
    }

    @Test fun everythingElseIsPassedThrough() {
        val inner = Inner()
        val b = DeleteOnCloseBridge(inner) { true }
        b.sendAudio("s-1", 0, ByteArray(4), false)
        b.events("s-1", null) { }
        b.fetchReplyAudio("s-1", 0)
        b.cancel("s-1")
        b.abortInFlight()
        assertEquals(listOf("send", "events", "fetch", "cancel", "abort"), inner.order)
    }
}
