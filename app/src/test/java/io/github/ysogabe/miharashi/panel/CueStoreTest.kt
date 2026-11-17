package io.github.ysogabe.miharashi.panel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 挨拶の取得とメモリでの保持(段 G。設計書(段階 2)§5.8.3・§5.8.6) */
class CueStoreTest {
    private fun wav(ms: Int, rate: Int = 48_000, amp: Short = 1000) = StartChime.wrap(ShortArray(rate * ms / 1000) { amp }, rate)

    private class FakeFetcher : CueFetcher {
        var wavs: MutableMap<String, ByteArray> = HashMap()
        var manifest: () -> CueManifest = { CueManifest("v1", wavs.mapValues { CueEntry(CueStore.sha256Hex(it.value), 2_000) }) }
        val requested = mutableListOf<String>()
        var manifestCalls = 0

        override fun fetchCueManifest(): CueManifest {
            manifestCalls++
            return manifest()
        }

        override fun fetchCue(name: String): ByteArray {
            requested += name
            return wavs[name] ?: throw BridgeException(BridgeFailure.NOT_FOUND, 404)
        }
    }

    private var now = 0L
    private val queued = ArrayDeque<Runnable>()

    /** 裏の取得を、明示して走らせる([runAll]) */
    private fun store(f: FakeFetcher) = CueStore(f, { queued.addLast(it) }, { now })

    private fun runAll() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private fun allNine(f: FakeFetcher) {
        for ((i, n) in StartCue.NAMES.withIndex()) f.wavs[n] = wav(1_000 + i * 100, amp = (100 + i).toShort())
    }

    @Test fun fetchesTheNineCuesAndKeepsThemInMemory() {
        val f = FakeFetcher().also(::allNine)
        val s = store(f)
        assertNull(s.get("g_day_swipe"))
        s.refreshIfDue()
        assertNull("待たない(裏で取る)", s.get("g_day_swipe"))
        runAll()
        for (n in StartCue.NAMES) assertArrayEquals(f.wavs[n], s.get(n))
        assertEquals(StartCue.NAMES.toSet(), f.requested.toSet())
    }

    @Test fun onlyTheKnownNamesAreRequestedEvenIfTheManifestListsOthers() {
        val f = FakeFetcher().also(::allNine)
        val extra = wav(1_000)
        f.manifest = {
            CueManifest("v1", f.wavs.mapValues { CueEntry(CueStore.sha256Hex(it.value), 1_000) } + ("g_evil" to CueEntry(CueStore.sha256Hex(extra), 1_000)))
        }
        val s = store(f)
        s.refreshIfDue(); runAll()
        assertFalse("g_evil" in f.requested)
        assertNull(s.get("g_evil"))
    }

    @Test fun aShaMismatchOrInvalidWavOrOverFiveSecondsIsRejected() {
        val f = FakeFetcher().also(::allNine)
        f.wavs["g_day_notice"] = ByteArray(200) { 7 } // WAV でない
        f.wavs["g_evening_notice"] = wav(5_001) // 5 秒超
        val tooLong = f.wavs["g_evening_notice"]!!
        val notWav = f.wavs["g_day_notice"]!!
        f.manifest = {
            val m = f.wavs.mapValues { CueEntry(CueStore.sha256Hex(it.value), 2_000) }.toMutableMap()
            m["g_day_swipe"] = CueEntry("0".repeat(64), 2_000) // sha256 の不一致
            CueManifest("v1", m)
        }
        val s = store(f)
        s.refreshIfDue(); runAll()
        assertNull("sha256 の不一致", s.get("g_day_swipe"))
        assertNull("WAV でない(${notWav.size})", s.get("g_day_notice"))
        assertNull("5 秒超(${tooLong.size})", s.get("g_evening_notice"))
        assertArrayEquals(f.wavs["g_morning_swipe"], s.get("g_morning_swipe"))
    }

    @Test fun acceptableChecksDeclaredAndActualLength() {
        val w = wav(5_000)
        assertTrue(CueStore.acceptable(w, CueEntry(CueStore.sha256Hex(w), 5_000)))
        assertFalse("宣言が 5 秒超", CueStore.acceptable(w, CueEntry(CueStore.sha256Hex(w), 5_001)))
        val long = wav(5_001)
        assertFalse("実データが 5 秒超(宣言は短い)", CueStore.acceptable(long, CueEntry(CueStore.sha256Hex(long), 1_000)))
        assertFalse(CueStore.acceptable(ByteArray(0), CueEntry(CueStore.sha256Hex(ByteArray(0)), 1_000)))
    }

    @Test fun aFailedRefreshIsRetriedOnTheNextEntryAndASuccessWaitsThirtyMinutes() {
        val f = FakeFetcher().also(::allNine)
        f.wavs.remove("g_retry") // 1 本欠ける: 失敗として扱う
        val s = store(f)
        s.refreshIfDue(); runAll()
        assertEquals(1, f.manifestCalls)
        assertNull(s.get("g_retry"))
        s.refreshIfDue(); runAll()
        assertEquals("失敗のあとは次の入場の前に取り直す", 2, f.manifestCalls)
        f.wavs["g_retry"] = wav(1_280)
        s.refreshIfDue(); runAll()
        assertEquals(3, f.manifestCalls)
        assertTrue(s.get("g_retry") != null)
        val requestsBefore = f.requested.size
        now += VoiceLimits.CUE_REFRESH_MS - 1
        s.refreshIfDue(); runAll()
        assertEquals("成功から 30 分は取り直さない", 3, f.manifestCalls)
        now += 1
        s.refreshIfDue(); runAll()
        assertEquals(4, f.manifestCalls)
        assertEquals("sha256 が同じものは取り直さない", requestsBefore, f.requested.size)
    }

    @Test fun aChangedShaIsRefetched() {
        val f = FakeFetcher().also(::allNine)
        val s = store(f)
        s.refreshIfDue(); runAll()
        val changed = wav(1_500, amp = 999)
        f.wavs["g_plain_swipe"] = changed
        now += VoiceLimits.CUE_REFRESH_MS
        val before = f.requested.size
        s.refreshIfDue(); runAll()
        assertEquals(listOf("g_plain_swipe"), f.requested.drop(before))
        assertArrayEquals(changed, s.get("g_plain_swipe"))
    }

    @Test fun aNotFoundManifestDropsTheCuesButANetworkFailureKeepsThem() {
        val f = FakeFetcher().also(::allNine)
        val s = store(f)
        s.refreshIfDue(); runAll()
        f.manifest = { throw BridgeException(BridgeFailure.NETWORK) }
        now += VoiceLimits.CUE_REFRESH_MS
        s.refreshIfDue(); runAll()
        assertTrue("通信の失敗では持っている分を使う", s.get("g_day_swipe") != null)
        f.manifest = { throw BridgeException(BridgeFailure.NOT_FOUND, 404) }
        s.refreshIfDue(); runAll()
        assertNull("404(挨拶を配っていない)ならチャイムにする", s.get("g_day_swipe"))
    }

    @Test fun onlyOneRefreshRunsAtATimeAndCloseStopsEverything() {
        val f = FakeFetcher().also(::allNine)
        var closed = 0
        val s = CueStore(f, { queued.addLast(it) }, { now }, onClose = { closed++ })
        s.refreshIfDue()
        s.refreshIfDue()
        assertEquals("同時に 1 本だけ", 1, queued.size)
        runAll()
        s.close()
        assertEquals(1, closed)
        assertNull(s.get("g_day_swipe"))
        s.refreshIfDue()
        assertTrue(queued.isEmpty())
    }

    @Test fun aRejectedRunnerDoesNotWedgeTheStore() {
        val f = FakeFetcher().also(::allNine)
        var reject = true
        val s = CueStore(f, { if (reject) throw java.util.concurrent.RejectedExecutionException() else queued.addLast(it) }, { now })
        s.refreshIfDue()
        reject = false
        s.refreshIfDue()
        assertEquals(1, queued.size)
    }

    @Test fun anExceptionInsideTheRefreshIsAFailure() {
        val f = FakeFetcher().also(::allNine)
        f.manifest = { throw IllegalStateException("boom") }
        val s = store(f)
        s.refreshIfDue(); runAll()
        f.manifest = { CueManifest("v1", f.wavs.mapValues { CueEntry(CueStore.sha256Hex(it.value), 2_000) }) }
        s.refreshIfDue(); runAll()
        assertTrue(s.get("g_day_swipe") != null)
    }
}
