package io.github.ysogabe.miharashi.panel

/**
 * 自動切替の候補になる、新着の通知 1 件(設計書 §4.4)。受信時刻を持つので、あとで再評価するときの経過時間を求められる。
 * `toString` は [DaidaiNotice] のものを使い、本文を含まない。
 */
data class NoticeCandidate(val notice: DaidaiNotice, val receivedAtMs: Long, val rev: Long) {
    /** 通知からの経過(ms)。ホストが計算した `age_s` に、受信からの経過を足す。 */
    fun ageMs(nowMs: Long): Long {
        // ageS はホストの値なので巨大でもあふれないよう、上限を超える分は切り詰める。
        val base = notice.ageS.coerceAtMost(NoticeTracker.AUTO_SWITCH_MAX_AGE_S + 1) * 1000L
        return base + (nowMs - receivedAtMs).coerceAtLeast(0L)
    }
}

/**
 * `KioskStatus.daidai` から新着の通知を取り出す(設計書 §4.4)。Android に依存せず、時刻は引数で受ける。単一スレッドで使う。
 *
 * - `rev` で新旧を判定する。既に見た最大の `rev` より小さいスナップショットは、表示用のデータも含めて丸ごと捨てる。
 *   同じ `rev` は許す(通知 id の重複排除があるので二重に切り替わらない)。
 * - `rev` が null(旧ホスト)のスナップショットは、表示用のデータだけ更新し、候補は出さない。
 * - 基準(baseline): [rebaseline] のあとで最初に正常に解釈できた(`daidai` のある)status に含まれる通知は、
 *   すべて既知にするだけで切り替えない。`daidai == null` の status は何もしない(基準も取らない)。
 * - 以降に新しく現れた id のうち、`age_s` が [AUTO_SWITCH_MAX_AGE_S] 以内で `low` でないものを候補にする。複数なら最新(先頭)。
 *   見送った通知も既知にする。
 */
class NoticeTracker {
    private val known = LinkedHashSet<String>()
    private var maxRev: Long? = null
    private var baselined = false

    private var latest: DaidaiInfo? = null

    /** 直近の通知一覧(新しい順、表示用)。 */
    val notices: List<DaidaiNotice> get() = latest?.notices ?: emptyList()

    /** 最新の通知(B のカード用)。 */
    val latestNotice: DaidaiNotice? get() = notices.firstOrNull()

    /** ホストが既読を管理するか。 */
    val ack: Boolean get() = latest?.ack ?: false

    /** ホストが数えた未読件数。`ack` が true のときだけ採用する(それ以外は null で、Panel が数える)。 */
    val unseen: Int? get() = latest?.takeIf { it.ack }?.unseen

    val avatarPose: String? get() = latest?.avatarPose

    /** 既に見た最大の `rev`。 */
    val rev: Long? get() = maxRev

    /** 既知の通知 id の数(診断・テスト用)。 */
    val knownCount: Int get() = known.size

    /** Panel が「見た」と数えた通知 id(R17、設計書 §4.4)。メモリのみ。基準の取り直しでは捨てない(表示用のデータと同じ寿命) */
    private val seen = LinkedHashSet<String>()

    /**
     * 未読の件数(画面 A の手掛かり用)。ホストが既読を管理する(`ack`)なら、ホストが数えた `unseen`。
     * `unseen` が不正で読めないときと、ホストが既読を管理しないときは、Panel が見ていない通知の数。
     */
    val unreadCount: Int
        get() = if (ack) unseen ?: localUnread else localUnread

    /** Panel のローカルの未読件数(直近の通知のうち、[markAllSeen] されていないもの) */
    val localUnread: Int get() = notices.count { it.id !in seen }

    /** 現在の窓(ホストが返す最新の通知)の id。新しい順 */
    val windowIds: List<String> get() = notices.map { it.id }

    /** 現在の窓のうち、Panel がまだ「見た」と数えていない id(新しい順)。ホストが既読を管理する場合の件数は [unseen] で別に決まる */
    val unreadIds: List<String> get() = notices.map { it.id }.filter { it !in seen }

    /**
     * 直近に採用した status で、直前の窓に無かった id(新着。新しい順)。件数ではなく id で見るので、
     * 窓が入れ替わって件数が変わらなくても、ホストの `unseen` が同数のままでも新着と分かる。捨てた(古い)status では変わらない。
     */
    var lastArrivals: List<String> = emptyList()
        private set

    /** 今ある通知をすべて既読にする(B を 2 秒以上見たとき)。あとで来た通知は、また未読になる。 */
    fun markAllSeen() {
        for (n in notices.asReversed()) {
            if (!seen.add(n.id)) continue
            while (seen.size > KNOWN_IDS_MAX) {
                val it = seen.iterator()
                it.next()
                it.remove()
            }
        }
    }

    /** 基準を取り直す(onResume、接続先の変更)。既知の id と最大の `rev` を捨てる。表示用のデータと既読は残る。 */
    fun rebaseline() {
        known.clear()
        maxRev = null
        baselined = false
    }

    fun onStatus(daidai: DaidaiInfo?, nowMs: Long): NoticeCandidate? {
        lastArrivals = emptyList()
        if (daidai == null) return null
        val rev = daidai.rev
        val seenRev = maxRev
        if (rev != null && seenRev != null && rev < seenRev) return null // 後着の古いスナップショット
        if (rev != null) maxRev = rev
        val previousIds = notices.mapTo(HashSet()) { it.id }
        lastArrivals = daidai.notices.map { it.id }.filter { it !in previousIds }
        latest = daidai

        val firstSnapshot = !baselined
        baselined = true

        val fresh = daidai.notices.filter { it.id !in known }
        // 古い順に入れるので、64 件を超えたとき捨てられるのは古いほう。
        for (n in daidai.notices.asReversed()) remember(n.id)

        if (firstSnapshot || rev == null) return null
        val picked = fresh.firstOrNull { it.priority != NoticePriority.LOW && it.ageS <= AUTO_SWITCH_MAX_AGE_S } ?: return null
        return NoticeCandidate(picked, nowMs, rev)
    }

    private fun remember(id: String) {
        if (!known.add(id)) return
        while (known.size > KNOWN_IDS_MAX) {
            val it = known.iterator()
            it.next()
            it.remove()
        }
    }

    companion object {
        /** 自動切替の対象にする通知の最大の経過秒(R20、設計書 §4.4)。 */
        const val AUTO_SWITCH_MAX_AGE_S = 30L

        /** 重複排除に使う既知 id の最大件数。超えたら古い順に捨てる。 */
        const val KNOWN_IDS_MAX = 64
    }
}
