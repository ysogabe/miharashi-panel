package io.github.ysogabe.miharashi.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DaidaiViewDrawingTest {
    private val notice = DaidaiNotice("n1", "おはよう", "今日は燃えるごみの日です", 3)

    private fun newView(w: Int = 960, h: Int = 480) =
        DaidaiView(RuntimeEnvironment.getApplication()).also { it.layout(0, 0, w, h) }

    private fun pixels(view: DaidaiView, w: Int = 960, h: Int = 480): IntArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h); bitmap.recycle() }
    }

    private fun draw(state: DaidaiUiState, contrast: Float = 0f): IntArray {
        val v = newView()
        v.contrastStrength = contrast
        v.render(state)
        return pixels(v)
    }

    private fun diff(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }

    private val states: Map<String, DaidaiUiState> = linkedMapOf(
        "idle" to DaidaiUiState(),
        "idleNotice" to DaidaiUiState(notice = notice, moreNotices = 2, noticePose = "talking"),
        "listening" to DaidaiUiState(voice = VoiceState.LISTENING, micActive = true),
        "hearing" to DaidaiUiState(voice = VoiceState.HEARING, micActive = true, transcript = "今日の天気は"),
        "waiting" to DaidaiUiState(voice = VoiceState.WAITING, transcript = "今日の天気は"),
        "replying" to DaidaiUiState(voice = VoiceState.REPLYING, transcript = "今日の天気は", reply = "今日は晴れでしょう"),
        "error" to DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.HOST_UNREACHABLE),
        "notConfigured" to DaidaiUiState.forSettings(false),
        "micPermission" to DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.MIC_PERMISSION),
    )

    @Test fun everyStateDrawsWithoutErrorAndDiffersFromTheOthers() {
        val pics = states.mapValues { draw(it.value) }
        val names = pics.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size)
            assertTrue("${names[i]} vs ${names[j]}", diff(pics.getValue(names[i]), pics.getValue(names[j])) > 200)
    }

    @Test fun backgroundIsTheSharedTokenAndHeaderIsThePanelColor() {
        val px = draw(DaidaiUiState())
        assertEquals(PanelView.BASE_BG, px[470 * 960 + 4]) // 左下の隅(ページ点の余白)
        assertEquals(PanelView.BASE_PANEL, px[4 * 960 + 480]) // ヘッダーの帯
    }

    @Test fun micInUseShowsAConspicuousRedBadgeInTheHeaderOnly() {
        val off = draw(DaidaiUiState(voice = VoiceState.LISTENING))
        val on = draw(DaidaiUiState(voice = VoiceState.LISTENING, micActive = true))
        fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 100 && Color.blue(c) < 100
        fun redInHeader(px: IntArray) = (0 until 56).sumOf { y -> (0 until 960).count { x -> isRed(px[y * 960 + x]) } }
        assertEquals(0, redInHeader(off))
        assertTrue("red dot in header: ${redInHeader(on)}", redInHeader(on) > 100)
        // 右半分のヘッダーにだけ出る
        assertEquals(0, (0 until 56).sumOf { y -> (0 until 600).count { x -> isRed(on[y * 960 + x]) } })
        // 下の領域は同じ(状態は変えていない)
        for (y in 60 until 480) for (x in 0 until 960) assertEquals("($x,$y)", off[y * 960 + x], on[y * 960 + x])
    }

    @Test fun contrastStrengthChangesColors() {
        for ((name, s) in states) {
            val dim = draw(s, 0f)
            val bright = draw(s, 1f)
            assertTrue(name, diff(dim, bright) > 1000)
        }
        // 背景は黒へ、本文は白へ寄る
        val bright = draw(DaidaiUiState(), 1f)
        assertEquals(Color.BLACK, bright[470 * 960 + 4])
        val half = draw(DaidaiUiState(), 0.5f)
        assertNotEquals(PanelView.BASE_BG, half[470 * 960 + 4])
        assertNotEquals(Color.BLACK, half[470 * 960 + 4])
    }

    @Test fun contrastStrengthIsClampedAndNanIsIgnored() {
        val v = newView()
        v.contrastStrength = Float.NaN
        assertEquals(0f, v.contrastStrength, 0f)
        v.contrastStrength = 7f
        assertEquals(1f, v.contrastStrength, 0f)
        v.contrastStrength = -3f
        assertEquals(0f, v.contrastStrength, 0f)
        pixels(v)
    }

    @Test fun avatarChangesWithTheStateEvenWhenTheRestIsTheSame() {
        // アバターの領域(左 300px)だけを見る: 待機 / 聞き取り / 応答待ち / 返答中で絵が違う
        fun avatarArea(s: DaidaiUiState): IntArray {
            val px = draw(s)
            return IntArray(300 * 400) { px[(70 + it / 300) * 960 + it % 300] }
        }
        val a = listOf(VoiceState.IDLE, VoiceState.LISTENING, VoiceState.WAITING, VoiceState.REPLYING)
            .map { avatarArea(DaidaiUiState(voice = it)) }
        for (i in a.indices) for (j in i + 1 until a.size) assertTrue("$i vs $j", diff(a[i], a[j]) > 20)
        // 通知のポーズ: 待機中のカード表示のとき、ポーズ名で絵が変わり、未知の名前は idle と同じ
        val idle = avatarArea(DaidaiUiState(notice = notice))
        val talking = avatarArea(DaidaiUiState(notice = notice, noticePose = "talking"))
        val unknown = avatarArea(DaidaiUiState(notice = notice, noticePose = "dancing"))
        assertTrue(diff(idle, talking) > 20)
        assertArrayEquals(idle, unknown)
    }

    @Test fun noticeCardAppearsOnlyWithANotice() {
        val lay = DaidaiLayout(960f, 480f, true)
        val card = lay.card!!
        val with = draw(DaidaiUiState(notice = notice))
        val without = draw(DaidaiUiState())
        val cx = (card.left + 8).toInt()
        val cy = (card.top + 8).toInt()
        assertEquals(PanelView.BASE_PANEL, with[cy * 960 + cx])
        assertEquals(PanelView.BASE_BG, without[cy * 960 + cx])
    }

    @Test fun moreNoticesCountIsShownOnlyWhenPositive() {
        val one = draw(DaidaiUiState(notice = notice, moreNotices = 0))
        val more = draw(DaidaiUiState(notice = notice, moreNotices = 3))
        assertTrue(diff(one, more) > 50)
    }

    @Test fun errorBubbleUsesTheFixedMessageNotTheHostText() {
        val a = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH, diagnostics = "host says: boom"))
        val b = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH, diagnostics = "totally different"))
        assertArrayEquals(a, b)
        val c = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.TIMEOUT))
        assertTrue(diff(a, c) > 50)
        // 赤い枠が出る
        val red = a.count { Color.red(it) > 240 && Color.green(it) in 80..100 && Color.blue(it) in 80..100 }
        assertTrue(red > 200)
    }

    // ---- 返答音声の失敗: 返答テキストを残し、下に固定文言を併記する

    private val replyText = "今日は晴れでしょう"

    /** 返答の領域のうち、1 行目の文字(大きさ 28、行の高さ 1.3 倍、上の余白 4)の帯と、その下に併記する帯(間隔 6 + 18 の文字) */
    private fun band(px: IntArray, top: Float, bottom: Float): IntArray {
        val box = DaidaiLayout(960f, 480f, false).reply
        val y0 = (box.top + top).toInt()
        val y1 = (box.top + bottom).toInt()
        return IntArray((y1 - y0) * (960 - box.left.toInt())) { i ->
            val w = 960 - box.left.toInt()
            px[(y0 + i / w) * 960 + box.left.toInt() + i % w]
        }
    }

    private fun textBand(px: IntArray) = band(px, 0f, 4f + 28f * 1.3f)
    private fun noteBand(px: IntArray) = band(px, 4f + 28f * 1.3f + 6f, 4f + 28f * 1.3f + 6f + 26f)
    private fun isWarn(c: Int) = Color.red(c) > 200 && Color.green(c) in 150..230 && Color.blue(c) < 130

    @Test fun replyAudioFailureKeepsTheReplyTextAndAddsTheFixedMessageBelow() {
        val ok = draw(DaidaiUiState(reply = replyText))
        val failed = draw(DaidaiUiState(reply = replyText, error = VoiceErrorCode.REPLY_AUDIO_FAILED, diagnostics = "tts: tts_failed"))
        // 返答テキストの帯は同じ(テキストは残っている。空でないことも確かめる)
        assertArrayEquals(textBand(ok), textBand(failed))
        assertTrue(textBand(failed).any { it != PanelView.BASE_BG })
        // 併記の帯: 失敗のときだけ警告色の文字が出る
        assertEquals(0, noteBand(ok).count { it != PanelView.BASE_BG })
        assertTrue(noteBand(failed).count(::isWarn) > 30)
        // 文言はコードから引く固定文言: 別のコードなら別の絵、診断の文字列は絵に影響しない
        val other = draw(DaidaiUiState(reply = replyText, error = VoiceErrorCode.TIMEOUT))
        assertTrue(diff(noteBand(failed), noteBand(other)) > 30)
        val sameCode = draw(DaidaiUiState(reply = replyText, error = VoiceErrorCode.REPLY_AUDIO_FAILED, diagnostics = "different"))
        assertArrayEquals(failed, sameCode)
        // ERROR 状態の吹き出しとは別の見た目(赤い枠は出ない)
        val bubble = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.REPLY_AUDIO_FAILED, reply = replyText))
        assertTrue(diff(failed, bubble) > 200)
        val red = failed.count { Color.red(it) > 240 && Color.green(it) in 80..100 && Color.blue(it) in 80..100 }
        assertEquals(0, red)
    }

    @Test fun errorBubbleIsUnchangedByAReplyText() {
        // ERROR 状態は従来どおり吹き出しだけ(返答テキストは描かない)
        val a = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH))
        val b = draw(DaidaiUiState(voice = VoiceState.ERROR, error = VoiceErrorCode.NO_SPEECH, reply = replyText))
        assertArrayEquals(a, b)
    }

    @Test fun replyAudioFailureWithoutReplyTextStillShowsTheMessage() {
        val plain = draw(DaidaiUiState())
        val failed = draw(DaidaiUiState(error = VoiceErrorCode.REPLY_AUDIO_FAILED))
        assertTrue(diff(plain, failed) > 100)
    }

    @Test fun longReplyWithFailureNoteStaysInsideTheReplyArea() {
        // 通知カードがあって返答の領域が最も狭いときに、4 行の返答 + 併記が領域の下にはみ出さない
        for (text in listOf("あ".repeat(2000), "a b ".repeat(500), "一\n二\n三\n四\n五\n六")) {
            val px = draw(DaidaiUiState(reply = text, notice = notice, error = VoiceErrorCode.REPLY_AUDIO_FAILED))
            val box = DaidaiLayout(960f, 480f, true).reply
            for (y in box.bottom.toInt() + 1 until 480) for (x in 300 until 960)
                assertEquals("($x,$y)", PanelView.BASE_BG, px[y * 960 + x])
            // 併記の文字(警告色)は返答の領域の中に出ている
            val warn = (box.top.toInt()..box.bottom.toInt()).sumOf { y -> (328 until 960).count { x -> isWarn(px[y * 960 + x]) } }
            assertTrue(warn > 30)
        }
    }

    @Test fun notConfiguredGuideHasNoChipAndNoCard() {
        val guide = draw(DaidaiUiState(notice = notice, setup = SetupGuide.NOT_CONFIGURED))
        val plain = draw(DaidaiUiState.forSettings(false))
        // 通知があっても案内が優先され、カードは出ない
        assertArrayEquals(plain, guide)
        val chip = DaidaiLayout(960f, 480f, false).chip
        val panelPixels = (chip.top.toInt()..chip.bottom.toInt()).sumOf { y ->
            (chip.left.toInt()..chip.right.toInt()).count { x -> guide[y * 960 + x] == PanelView.BASE_PANEL }
        }
        assertEquals(0, panelPixels)
    }

    @Test fun longReplyAndHostileTextDoNotBreakDrawing() {
        val hostile = "\u202E" + "あ".repeat(2500) + "\u0007\u2066"
        val v = newView()
        v.render(DaidaiUiState(voice = VoiceState.REPLYING, reply = hostile, transcript = hostile,
            notice = DaidaiNotice("n", "\u202Eタイトル", "本文\n\n\n\n\n\n\n本文", 1), moreNotices = 99999))
        pixels(v)
        v.render(DaidaiUiState(voice = VoiceState.REPLYING, reply = "a b ".repeat(1000)))
        pixels(v)
        v.render(DaidaiUiState(voice = VoiceState.ERROR, error = null))
        pixels(v)
    }

    @Test fun bidiControlsAreRemovedBeforeDrawing() {
        val clean = draw(DaidaiUiState(voice = VoiceState.REPLYING, reply = "こんにちは"))
        val dirty = draw(DaidaiUiState(voice = VoiceState.REPLYING, reply = "\u202E\u202A\u0007こん\u2066に\u2069ち\u200Bは\u202C"))
        assertArrayEquals(clean, dirty)
    }

    @Test fun replyIsPlainTextNotHtml() {
        val view = newView()
        view.render(DaidaiUiState(voice = VoiceState.REPLYING, reply = "<b>bold</b> &amp; <script>x</script>"))
        pixels(view) // 例外なし。描画文字列がそのまま使われる(タグは解釈しない)
        assertEquals("<b>bold</b> &amp; <script>x</script>", view.uiState.reply)
    }

    /** ShadowView で invalidate の有無を見る。`draw` は invalidate フラグを下ろさないので、観測のたびに自分で下ろす */
    private fun DaidaiView.invalidated(): Boolean = shadowOf(this).wasInvalidated().also { shadowOf(this).clearWasInvalidated() }

    @Test fun sameStateDoesNotInvalidateAgainButAChangeDoes() {
        val v = newView()
        v.invalidated() // 初期化で付いた分を下ろす
        val first = DaidaiUiState(voice = VoiceState.REPLYING, reply = "今日は晴れでしょう", notice = notice, moreNotices = 2)
        v.render(first)
        assertTrue(v.invalidated())
        val before = pixels(v)

        // 別のインスタンスだが値が等しい状態を渡しても、描き直さない
        val equal = DaidaiUiState(voice = VoiceState.REPLYING, reply = "今日は晴れでしょう", notice = notice.copy(), moreNotices = 2)
        assertNotSame(first, equal)
        assertEquals(first, equal)
        v.render(equal)
        v.render(equal)
        assertFalse(v.invalidated())
        assertArrayEquals(before, pixels(v))

        // 状態が変われば描き直す
        v.render(equal.copy(reply = "雨でしょう"))
        assertTrue(v.invalidated())
        v.render(first)
        v.invalidated()

        // 照度の補正も、同じ値なら描き直さず、変えれば描き直してピクセルが変わる
        v.contrastStrength = 0f
        assertFalse(v.invalidated())
        v.contrastStrength = 1f
        assertTrue(v.invalidated())
        assertTrue(diff(before, pixels(v)) > 1000)
        v.contrastStrength = 1f
        assertFalse(v.invalidated())
    }

    @Test fun otherSizesWork() {

        val v = newView(1024, 600)
        v.render(DaidaiUiState(notice = notice, moreNotices = 2))
        pixels(v, 1024, 600)
        val small = newView(480, 240)
        small.render(states.getValue("replying"))
        pixels(small, 480, 240)
        val unsized = DaidaiView(RuntimeEnvironment.getApplication())
        unsized.render(states.getValue("error"))
        unsized.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))) // 大きさ 0 でも落ちない
    }
}
