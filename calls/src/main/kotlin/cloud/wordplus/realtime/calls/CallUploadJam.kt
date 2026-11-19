package cloud.wordplus.realtime.calls

/**
 * Whether the upload this side sends on is jammed, so the person can be told: what they send waits seconds on their own
 * line, or gets no answer at all, while what is sent to them still arrives. The browsers' engine judges it the same way
 * with the same numbers, and says what was seen and measured (realtime-client, calls/upload-jam.ts): a round trip of
 * seconds is the line's, and the one who can mend it is the person, whatever else uploads on their network.
 *
 * A reading is the selected candidate pair of the connection this side sends on, as the room's microphone reader
 * already has it. Jammed takes every reading saying so for [JAM_FOR_MS]: the pair's round trip [RTT_MS] or more, or its
 * checks asked and none answered for [UNANSWERED_MS] (a check's answer waits in the same queue, so the round trip it
 * would read comes late, if at all), while something still arrives on it, so a connection that is gone altogether,
 * which the call already shows as reconnecting, isn't called jammed. It clears after [CLEAR_FOR_MS] of readings with the
 * round trip under [CLEAR_RTT_MS] and the checks answered. A pair whose counts go back is a new one (a resume restarts
 * ICE): its counts start again and what was decided stays.
 */
internal class CallUploadJam {
    /**
     * One reading of the connection this side sends on, at [at] ms: the pair's round trip in ms, its checks sent so far
     * and those answered, and what has arrived on it so far in bytes. Anything not known is null.
     */
    data class Reading(val at: Long, val rttMs: Long? = null, val requests: Long? = null, val responses: Long? = null, val bytesReceived: Long? = null)

    private var on = false

    /** When the readings began to say so, in a row. */
    private var since: Long? = null
    private var clearSince: Long? = null
    private var last: Reading? = null

    /** When a check was last answered, or counts began, and the checks sent then. */
    private var answeredAt = 0L
    private var requestsThen = 0L

    /** Whether the upload is jammed now. */
    val jammed: Boolean
        get() = on

    /** One reading. Answers whether [jammed] changed. */
    fun review(r: Reading): Boolean {
        val last = this.last

        this.last = r

        if (last == null ||
            (r.requests ?: 0L) < (last.requests ?: 0L) ||
            (r.responses ?: 0L) < (last.responses ?: 0L) ||
            (r.bytesReceived ?: 0L) < (last.bytesReceived ?: 0L)
        ) {
            answeredAt = r.at
            requestsThen = r.requests ?: 0L

            return false
        }

        if ((r.responses ?: 0L) > (last.responses ?: 0L)) {
            answeredAt = r.at
            requestsThen = r.requests ?: 0L
        }

        val unanswered = (r.requests ?: 0L) > requestsThen && r.at - answeredAt >= UNANSWERED_MS
        val arrives = (r.bytesReceived ?: 0L) > (last.bytesReceived ?: 0L)
        val slow = (r.rttMs ?: 0L) >= RTT_MS

        if (!on) {
            if (!(arrives && (slow || unanswered))) {
                since = null

                return false
            }

            val began = since ?: r.at

            since = began

            if (r.at - began < JAM_FOR_MS) return false

            on = true
            since = null
            clearSince = null

            return true
        }

        // a client that gives no round trip clears on its checks answered alone
        val clear = !unanswered && (r.rttMs == null || r.rttMs < CLEAR_RTT_MS)

        if (!clear) {
            clearSince = null

            return false
        }

        val began = clearSince ?: r.at

        clearSince = began

        if (r.at - began < CLEAR_FOR_MS) return false

        on = false
        clearSince = null

        return true
    }

    companion object {
        /** A round trip from which the upload counts as jammed, ms. */
        const val RTT_MS = 3_000L

        /** How long checks asked and none answered count as jammed, ms. */
        const val UNANSWERED_MS = 6_000L

        /** How long every reading has to say so before it counts, ms: a moment's spike of seconds is no jam to tell anyone about. */
        const val JAM_FOR_MS = 6_000L

        /** The round trip under which a reading counts as clear, ms. */
        const val CLEAR_RTT_MS = 1_000L

        /** How long readings have to be clear before it is no longer jammed, ms. */
        const val CLEAR_FOR_MS = 10_000L
    }
}
