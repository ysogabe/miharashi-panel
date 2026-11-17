package io.github.ysogabe.miharashi.panel

/**
 * 開始の合図の選び方(段 G。設計書(段階 2)§5.8.1・§5.8.2)。純粋な関数で、時刻・時差・「どの時間帯で挨拶したか」は呼ぶ側が渡す。
 *
 * - 場面([CueScene]): スワイプ・通知の自動切替・ERROR のタップでの再試行。再試行は、前の会話の `reason` が `notification` でも [CueScene.RETRY]。
 * - 時間帯([Daypart]): 端末の時計で 4:00〜10:59 は朝、11:00〜17:59 は昼、18:00〜翌 3:59 は夜。
 * - 時間帯の挨拶は、**その時間帯で最初の 1 回だけ**。あとは短い形(`g_plain_*`)。スワイプと通知で共通に数える。再試行は数えず、数えた分も使わない。
 * - 時間帯の印([daypartKey])は「4 時間ずらした日付 + 時間帯」。夜は日付をまたぐ(23 時と 1 時は同じ夜)ので、暦の日付ではなく 4:00 始まりの日で数える。
 * - 通知の場面の文言は「お知らせが届いています」系。通知の本文は読み上げない(事前合成の固定の文言だけ)。
 */
enum class CueScene(val wire: String) { SWIPE("swipe"), NOTIFICATION("notice"), RETRY("retry") }

enum class Daypart(val wire: String) { MORNING("morning"), DAY("day"), EVENING("evening") }

/**
 * 選んだ合図。[name] はホストの目録の名前(9 本のどれか)。[daypartKey] は時間帯の挨拶を選んだときだけ非 null で、
 * 挨拶を実際に鳴らし始めたら、呼ぶ側がこれを「挨拶済みの時間帯」として覚える。
 */
data class CueChoice(val name: String, val daypartKey: Long?)

object StartCue {
    const val RETRY = "g_retry"

    /** Panel が取りに行く挨拶の名前(これ以外の名前は URL に入れない) */
    val NAMES: List<String> = buildList {
        for (scene in listOf(CueScene.SWIPE, CueScene.NOTIFICATION)) {
            for (d in Daypart.entries) add("g_${d.wire}_${scene.wire}")
            add("g_plain_${scene.wire}")
        }
        add(RETRY)
    }

    private const val MINUTES_PER_DAY = 24 * 60
    private const val DAY_START_MINUTE = 4 * 60 // 4:00
    private const val DAY_MINUTE = 11 * 60 // 11:00
    private const val EVENING_MINUTE = 18 * 60 // 18:00
    private const val DAY_MS = 86_400_000L

    /** 端末の時刻(エポックのミリ秒と時差)の時間帯 */
    fun daypart(epochMs: Long, offsetMs: Long): Daypart = daypartOfMinute(ClockMath.minuteOfDay(epochMs, offsetMs))

    fun daypartOfMinute(minute: Int): Daypart {
        val m = Math.floorMod(minute, MINUTES_PER_DAY)
        return when {
            m in DAY_START_MINUTE until DAY_MINUTE -> Daypart.MORNING
            m in DAY_MINUTE until EVENING_MINUTE -> Daypart.DAY
            else -> Daypart.EVENING
        }
    }

    /** 時間帯の印: 4:00 始まりの日の番号 × 3 + 時間帯。0:00〜3:59 は前の日の夜と同じ印になる */
    fun daypartKey(epochMs: Long, offsetMs: Long): Long {
        val local = epochMs + offsetMs
        val day = Math.floorDiv(local - DAY_START_MINUTE * 60_000L, DAY_MS)
        return day * 3 + daypart(epochMs, offsetMs).ordinal
    }

    /**
     * 合図を選ぶ。`greetedKey`: 最後に時間帯の挨拶を鳴らした時間帯の印(無ければ null)。
     * 再試行は常に [RETRY](時間帯の印を返さない)。スワイプ・通知は、今の時間帯で未だ挨拶していなければ時間帯の挨拶、していれば短い形。
     */
    fun choose(scene: CueScene, epochMs: Long, offsetMs: Long, greetedKey: Long?): CueChoice {
        if (scene == CueScene.RETRY) return CueChoice(RETRY, null)
        val key = daypartKey(epochMs, offsetMs)
        return if (greetedKey == key) CueChoice("g_plain_${scene.wire}", null)
        else CueChoice("g_${daypart(epochMs, offsetMs).wire}_${scene.wire}", key)
    }
}
