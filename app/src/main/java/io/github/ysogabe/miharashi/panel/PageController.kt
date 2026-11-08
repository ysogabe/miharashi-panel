package io.github.ysogabe.miharashi.panel

/** 2 画面。A は現行の操作盤、B はだいだい連携画面。 */
enum class Page { A, B }

/** ページが変わった理由(設計書 §4.3)。 */
enum class PageReason { SWIPE, AUTO_NOTICE, IDLE_RETURN, RESUME }

/** [PageController] の通知。すべてメインスレッドで呼ばれる。 */
interface PageListener {
    /**
     * ページの移動が終わった(アニメーション終了時に一度だけ)。**B への入場処理(音声セッションの開始など)はここでだけ行う。**
     * 同じページへ戻っただけ(スワイプを途中でやめた)のときは呼ばれない。ドラッグ中に B が半分見えている間も呼ばれない
     */
    fun onSettled(page: Page, reason: PageReason) {}

    /** B が少しでも見えるか(ドラッグ中・アニメーション中・B に落ち着いている間)が変わった。A の無操作判定の保留に使う */
    fun onBVisibleChanged(visible: Boolean) {}

    /** [PageController.goTo] か指を離したことで、`target` へのアニメーションを始めてほしい */
    fun onMoveRequested(target: Page, reason: PageReason) {}

    /** アニメーションなしで A に戻した(背面・前面への復帰)。進行中のアニメーションは捨てる */
    fun onSnap(page: Page) {}
}

/**
 * 現在のページと遷移の状態(設計書 §4.1, §4.3)。純 Kotlin。時刻は呼び出し側が渡す。
 *
 * 見た目(ドラッグの追従とアニメーション)は `PagerLayout` が担当し、この型は「どのページにいるか」と
 * 「いつ落ち着いたか」だけを持つ。後続の処理は [PageListener.onSettled] だけを見ればよい。
 *
 * B の無操作タイマー(`idleMs`)は B に落ち着いている間だけ数え、[sessionActive] が true の間は止まる。
 */
class PageController(idleMs: Long = DEFAULT_IDLE_MS) {
    enum class Phase { IDLE, DRAGGING, ANIMATING }

    /** B の無操作でAへ戻すまでの時間(ms)。0 以下なら戻さない */
    var idleMs: Long = idleMs

    /** 落ち着いているページ。アニメーション中は移動前のページ */
    var settledPage = Page.A
        private set

    /** 向かっているページ。落ち着いていれば [settledPage] と同じ */
    var targetPage = Page.A
        private set

    var phase = Phase.IDLE
        private set

    /** 利用者のドラッグ中か、移動のアニメーション中。T5 の自動切替はこの間は見送り、落ち着いてから評価し直す */
    val userBusy: Boolean get() = phase != Phase.IDLE

    /** B が少しでも見えている */
    val bVisible: Boolean get() = settledPage == Page.B || phase != Phase.IDLE

    /** 音声セッションが進行中か。true の間は B の無操作タイマーを止める(T3 の時点では常に false) */
    var sessionActive = false
        set(value) {
            if (field && !value) rearm = true
            field = value
        }

    private var lastActivityMs = 0L
    private var rearm = false
    private var reason = PageReason.SWIPE
    private var lastBVisible = false
    private val listeners = mutableListOf<PageListener>()

    fun addListener(l: PageListener) { listeners += l }
    fun removeListener(l: PageListener) { listeners -= l }

    /** 指が触れた(どのページでも)。B の無操作タイマーを数え直す */
    fun touch(nowMs: Long) { lastActivityMs = nowMs }

    /** 水平ドラッグが始まった。アニメーション中は始められない(false) */
    fun beginDrag(nowMs: Long): Boolean {
        when (phase) {
            Phase.ANIMATING -> return false
            Phase.DRAGGING -> return true
            Phase.IDLE -> Unit
        }
        phase = Phase.DRAGGING
        targetPage = settledPage
        reason = PageReason.SWIPE
        touch(nowMs)
        fireVisibility()
        return true
    }

    /** 指を離した(取り消しも含む)。`target` へ(元のページなら戻す)アニメーションを始めさせる */
    fun endDrag(target: Page, nowMs: Long) {
        if (phase != Phase.DRAGGING) return
        phase = Phase.ANIMATING
        targetPage = target
        reason = PageReason.SWIPE
        touch(nowMs)
        emitMove(target, PageReason.SWIPE)
    }

    /**
     * プログラムからの切替(T5 の自動切替、無操作での復帰)。受け付けて動き出す、または既に同じページへ向かっているなら true。
     * ドラッグ中、または別のページへ向かうアニメーション中は何もしない(false)。落ち着いたページと同じでも false
     */
    fun goTo(page: Page, reason: PageReason, nowMs: Long): Boolean {
        when (phase) {
            Phase.DRAGGING -> return false
            Phase.ANIMATING -> return targetPage == page
            Phase.IDLE -> Unit
        }
        if (page == settledPage) return false
        phase = Phase.ANIMATING
        targetPage = page
        this.reason = reason
        touch(nowMs)
        fireVisibility()
        emitMove(page, reason)
        return true
    }

    /** `PagerLayout` がアニメーションを終えた。ページが変わっていれば [PageListener.onSettled] を一度だけ呼ぶ */
    fun animationEnded(nowMs: Long) {
        if (phase != Phase.ANIMATING) return
        val from = settledPage
        val why = reason
        phase = Phase.IDLE
        settledPage = targetPage
        if (settledPage == Page.B) touch(nowMs)
        fireVisibility()
        if (settledPage != from) for (l in listeners.toList()) l.onSettled(settledPage, why)
    }

    /**
     * 時間を進める(B に落ち着いている間は定期的に呼ぶ)。B で `idleMs` 無操作なら A へ戻し始めて true。
     * セッション進行中は止まり、終わった後の最初の呼び出しから数え直す
     */
    fun tick(nowMs: Long): Boolean {
        if (settledPage != Page.B || phase != Phase.IDLE) return false
        if (sessionActive) { lastActivityMs = nowMs; return false }
        if (rearm) { rearm = false; lastActivityMs = nowMs; return false }
        if (idleMs <= 0 || nowMs - lastActivityMs < idleMs) return false
        return goTo(Page.A, PageReason.IDLE_RETURN, nowMs)
    }

    /** 背面へ回る。A に戻し、進行中のアニメーションは捨てる(画面状態は保存しない既存の方針)。 */
    fun onPause(nowMs: Long) = resetToA(nowMs)

    /** 前面へ戻る。常に A から始める(Intent からページは指定できない)。 */
    fun onResume(nowMs: Long) = resetToA(nowMs)

    private fun resetToA(nowMs: Long) {
        val wasB = settledPage == Page.B
        phase = Phase.IDLE
        settledPage = Page.A
        targetPage = Page.A
        reason = PageReason.RESUME
        rearm = false
        lastActivityMs = nowMs
        for (l in listeners.toList()) l.onSnap(Page.A)
        fireVisibility()
        if (wasB) for (l in listeners.toList()) l.onSettled(Page.A, PageReason.RESUME)
    }

    private fun emitMove(target: Page, why: PageReason) {
        for (l in listeners.toList()) l.onMoveRequested(target, why)
    }

    private fun fireVisibility() {
        val v = bVisible
        if (v == lastBVisible) return
        lastBVisible = v
        for (l in listeners.toList()) l.onBVisibleChanged(v)
    }

    companion object {
        const val DEFAULT_IDLE_MS = Settings.DEFAULT_IDLE_SECONDS * 1000L
    }
}
