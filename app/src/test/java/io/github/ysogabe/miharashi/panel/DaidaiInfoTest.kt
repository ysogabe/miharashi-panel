package io.github.ysogabe.miharashi.panel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `KioskStatus.parse` の任意の `daidai` ブロック(設計書 §5.3、AC12 の parse 側)。 */
class DaidaiInfoTest {
    private fun notice(
        id: Any? = "n-1",
        title: Any? = "t",
        body: Any? = "b",
        age: Any? = 1,
        priority: Any? = "normal",
    ) = JSONObject().apply {
        if (id != null) put("id", id)
        if (title != null) put("title", title)
        if (body != null) put("body", body)
        if (age != null) put("age_s", age)
        if (priority != null) put("priority", priority)
    }

    /** photo の fixture に `daidai` を差し込んだ status。 */
    private fun statusWith(daidai: Any?): String {
        val o = JSONObject(fixture("status-photo.json"))
        if (daidai != null) o.put("daidai", daidai)
        return o.toString()
    }

    private fun parseDaidai(block: JSONObject) = KioskStatus.parse(statusWith(block)).daidai

    private fun block(vararg n: JSONObject, rev: Any? = 1) =
        JSONObject().put("rev", rev).put("notices", JSONArray().apply { n.forEach { put(it) } })

    // ---- fixtures ----

    @Test
    fun parsesFixtureWithNotices() {
        val s = KioskStatus.parse(fixture("status-daidai.json"))
        val d = s.daidai!!
        assertEquals(128L, d.rev)
        assertEquals(2, d.notices.size)
        assertEquals(
            DaidaiNotice("n-20261108-0731", "おはよう", "今日は燃えるごみの日です", 12, NoticePriority.NORMAL),
            d.notices[0],
        )
        assertEquals("n-20261108-0630", d.notices[1].id)
        assertEquals(2, d.unseen)
        assertTrue(d.ack)
        assertEquals("idle", d.avatarPose)
        assertEquals("geoframe-n100", s.host)
        assertEquals(listOf("status", "photo"), s.screens.map { it.id })
    }

    @Test
    fun parsesEmptyLowAndStaleFixtures() {
        val empty = KioskStatus.parse(fixture("status-daidai-empty.json")).daidai!!
        assertEquals(129L, empty.rev)
        assertTrue(empty.notices.isEmpty())
        assertEquals(0, empty.unseen)

        val low = KioskStatus.parse(fixture("status-daidai-low.json")).daidai!!
        assertEquals(NoticePriority.LOW, low.notices.single().priority)
        assertFalse(low.ack)

        val stale = KioskStatus.parse(fixture("status-daidai-stale-rev.json")).daidai!!
        assertEquals(120L, stale.rev)
        assertTrue(stale.rev!! < KioskStatus.parse(fixture("status-daidai.json")).daidai!!.rev!!)
    }

    @Test
    fun oldStatusWithoutDaidaiIsUnchanged() {
        val old = KioskStatus.parse(fixture("status-photo.json"))
        assertNull(old.daidai)
        assertEquals(6 to 528, old.photo)
        // 既存 fixture すべてで daidai は null
        listOf("status-details", "status-display", "status-five-screens", "status-keys-aircraft", "status-menu-open",
            "status-rotation-on", "status-starting").forEach { assertNull(it, KioskStatus.parse(fixture("$it.json")).daidai) }
    }

    @Test
    fun unknownDaidaiFieldsAreIgnored() {
        val d = parseDaidai(block(notice()).put("future", JSONObject().put("x", 1)))!!
        assertEquals(1, d.notices.size)
        val n = notice().put("extra", "x")
        assertEquals(1, parseDaidai(block(n))!!.notices.size)
    }

    // ---- ブロック自体が壊れている ----

    @Test
    fun brokenBlockMakesOnlyDaidaiNull() {
        listOf<Any>("text", 5, true, JSONArray().put(1), JSONObject.NULL).forEach { v ->
            val s = KioskStatus.parse(statusWith(v))
            assertNull("daidai=$v", s.daidai)
            assertEquals("status 全体は読めている", 6 to 528, s.photo)
        }
    }

    @Test
    fun brokenInnerFieldsDoNotFailTheBlock() {
        val d = parseDaidai(
            JSONObject().put("rev", "x").put("notices", "not an array").put("unseen", -3).put("ack", "yes").put("avatar_pose", 5),
        )!!
        assertNull(d.rev)
        assertTrue(d.notices.isEmpty())
        assertNull(d.unseen)
        assertFalse(d.ack)
        assertNull(d.avatarPose)
        val noFields = parseDaidai(JSONObject())!!
        assertEquals(DaidaiInfo(null, emptyList(), null, false, null), noFields)
    }

    // ---- rev / unseen / ack ----

    @Test
    fun revMustBeNonNegativeInteger() {
        assertEquals(0L, parseDaidai(block(rev = 0))!!.rev)
        assertEquals(5_000_000_000L, parseDaidai(block(rev = 5_000_000_000L))!!.rev)
        listOf<Any?>(-1, 1.5, "3", true, JSONObject.NULL, null).forEach {
            assertNull("rev=$it", parseDaidai(block(rev = it))!!.rev)
        }
    }

    @Test
    fun unseenAndAck() {
        val d = parseDaidai(block().put("unseen", 3).put("ack", true))!!
        assertEquals(3, d.unseen)
        assertTrue(d.ack)
        assertNull(parseDaidai(block().put("unseen", 1.5))!!.unseen)
        assertNull(parseDaidai(block().put("unseen", 5_000_000_000L))!!.unseen)
        assertFalse("省略は false", parseDaidai(block())!!.ack)
    }

    // ---- notices ----

    @Test
    fun keepsAtMostFiveNoticesInOrder() {
        val ns = (1..8).map { notice(id = "n$it") }.toTypedArray()
        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), parseDaidai(block(*ns))!!.notices.map { it.id })
    }

    @Test
    fun invalidIdsAreDropped() {
        val ok = listOf("a", "A-b_c.d:e", "x".repeat(64), "0")
        val bad = listOf("", "x".repeat(65), "a b", "a/b", "a\n", "日本語", "a;b", "<script>")
        val d = parseDaidai(block(*(ok + bad).map { notice(id = it) }.toTypedArray()))!!
        assertEquals(ok.take(DaidaiInfo.MAX_NOTICES), d.notices.map { it.id })
        val onlyBad = parseDaidai(block(*bad.map { notice(id = it) }.toTypedArray()))!!
        assertTrue(onlyBad.notices.isEmpty())
        // 文字列でない id、id なし
        assertTrue(parseDaidai(block(notice(id = 12), notice(id = null)))!!.notices.isEmpty())
    }

    @Test
    fun invalidNoticeDoesNotTakeASlotFromValidOnes() {
        val ns = listOf(notice(id = "bad id")) + (1..5).map { notice(id = "n$it") }
        assertEquals(5, parseDaidai(block(*ns.toTypedArray()))!!.notices.size)
    }

    @Test
    fun duplicateIdKeepsFirst() {
        val d = parseDaidai(block(notice(id = "x", title = "first"), notice(id = "x", title = "second")))!!
        assertEquals(listOf("first"), d.notices.map { it.title })
    }

    @Test
    fun ageMustBeNonNegativeInteger() {
        assertEquals(0L, parseDaidai(block(notice(age = 0)))!!.notices.single().ageS)
        assertEquals(99L, parseDaidai(block(notice(age = 99)))!!.notices.single().ageS)
        listOf<Any?>(-1, 1.5, "5", null, true).forEach {
            assertTrue("age_s=$it", parseDaidai(block(notice(age = it)))!!.notices.isEmpty())
        }
    }

    @Test
    fun priorityDefaultsToNormal() {
        fun p(v: Any?) = parseDaidai(block(notice(priority = v)))!!.notices.single().priority
        assertEquals(NoticePriority.NORMAL, p("normal"))
        assertEquals(NoticePriority.LOW, p("low"))
        assertEquals(NoticePriority.NORMAL, p(null))
        assertEquals(NoticePriority.NORMAL, p("high"))
        assertEquals(NoticePriority.NORMAL, p("LOW"))
        assertEquals(NoticePriority.NORMAL, p(3))
    }

    @Test
    fun truncatesTitleAndBodyByCodePoints() {
        val d = parseDaidai(block(notice(title = "あ".repeat(100), body = "い".repeat(500))))!!
        assertEquals(40, d.notices.single().title.length)
        assertEquals(200, d.notices.single().body.length)
        assertEquals("あ".repeat(40), d.notices.single().title)

        val exact = parseDaidai(block(notice(title = "t".repeat(40), body = "b".repeat(200))))!!.notices.single()
        assertEquals(40, exact.title.length)
        assertEquals(200, exact.body.length)

        // サロゲートペアを途中で割らない
        val emoji = "😀" // 😀 (1 コードポイント、2 char)
        val e = parseDaidai(block(notice(title = emoji.repeat(50), body = emoji.repeat(300))))!!.notices.single()
        assertEquals(40, e.title.codePointCount(0, e.title.length))
        assertEquals(80, e.title.length)
        assertEquals(200, e.body.codePointCount(0, e.body.length))
    }

    @Test
    fun hugeTextIsCutWithoutReadingItAll() {
        val d = parseDaidai(block(notice(title = "a".repeat(2_000_000), body = "b".repeat(2_000_000))))!!.notices.single()
        assertEquals(40, d.title.length)
        assertEquals(200, d.body.length)
    }

    @Test
    fun stripsControlAndBidiCharacters() {
        val bidi = "‪‫‬‭‮⁦⁧⁨⁩‎‏؜"
        val ctl = "\u0000\u0001\u0007\u001B\u007F\u009F"
        val n = parseDaidai(block(notice(title = "a${bidi}b${ctl}c", body = "x${bidi}y${ctl}z")))!!.notices.single()
        assertEquals("abc", n.title)
        assertEquals("xyz", n.body)
    }

    @Test
    fun titleHasNoNewlinesButBodyKeepsThem() {
        val n = parseDaidai(block(notice(title = "a\nb\r\nc\rd e f", body = "1\n2\r\n3\r4\t5  6")))!!.notices.single()
        assertEquals("abcdef", n.title)
        assertEquals("1\n2\n3\n45\n 6", n.body)
        assertFalse(n.title.any { it == '\n' || it == '\r' })
    }

    @Test
    fun trimsAndRemovesLoneSurrogates() {
        val n = parseDaidai(block(notice(title = "  　hi \n ", body = "\n\n  x\uD800y\uDC00z \n")))!!.notices.single()
        assertEquals("hi", n.title)
        assertEquals("xyz", n.body)
    }

    @Test
    fun noticeWithNothingToShowIsDropped() {
        assertTrue(parseDaidai(block(notice(title = "‮\n", body = "\u0000")))!!.notices.isEmpty())
        assertTrue(parseDaidai(block(notice(title = null, body = null)))!!.notices.isEmpty())
        assertEquals("t", parseDaidai(block(notice(title = "t", body = null)))!!.notices.single().title)
        val numericTitle = parseDaidai(block(notice(title = 5, body = "b")))!!.notices.single()
        assertEquals("", numericTitle.title)
        assertEquals("b", numericTitle.body)
        val bodyOnly = parseDaidai(block(notice(title = null, body = "only")))!!.notices.single()
        assertEquals("", bodyOnly.title)
        assertEquals("only", bodyOnly.body)
    }

    @Test
    fun nonObjectNoticeEntriesAreSkipped() {
        val arr = JSONArray().put("x").put(3).put(JSONObject.NULL).put(notice(id = "ok"))
        val d = parseDaidai(JSONObject().put("rev", 1).put("notices", arr))!!
        assertEquals(listOf("ok"), d.notices.map { it.id })
    }

    // ---- avatar_pose ----

    @Test
    fun avatarPoseIsRestrictedToShortSafeStrings() {
        fun pose(v: Any?) = parseDaidai(JSONObject().put("avatar_pose", v))!!.avatarPose
        assertEquals("talking", pose("talking"))
        assertEquals("a-b_C9", pose("a-b_C9"))
        assertEquals("x".repeat(32), pose("x".repeat(32)))
        assertNull(pose("x".repeat(33)))
        assertNull(pose(""))
        assertNull(pose("a b"))
        assertNull(pose("../x"))
        assertNull(pose("<b>"))
        assertNull(pose("a\n"))
        assertNull(pose("ポーズ"))
    }

    // ---- ログに出ない ----

    @Test
    fun toStringDoesNotLeakNoticeText() {
        val d = KioskStatus.parse(fixture("status-daidai.json"))
        assertNotNull(d.daidai)
        val s = d.toString()
        assertFalse(s.contains("燃えるごみ"))
        assertFalse(s.contains("おはよう"))
    }
}
