package cloud.wordplus.realtime.calls

/**
 * What a call lets its microphone spend, on an upload too slow for 64 kbps of it. The browsers' engine
 * does the same with the same numbers, and says why and what was measured (realtime-client, calls/mic-ceiling.ts): the
 * allocation counts a microphone once at its ceiling while in RED it sends about twice that, so on a slow upload the
 * camera's share overcommits the link and the picture freezes.
 *
 * The ceiling comes down only while video goes out and the estimate has stayed low, so a call's first seconds leave it
 * alone, and it goes back up once the estimate is clear of the mark or no video goes out any more. A call with no video
 * keeps all of it: nothing competes with its sound, and its estimate is low only because it sends little.
 *
 * A room's marks sit higher, as the browsers' do: a simulcast camera pads what its picture doesn't need up to about the
 * estimate, and the microphone goes on top of that.
 *
 * A room's microphone can be too much for its line all by itself, with no video going out and no estimate to say
 * anything: then its node's count of what it lost brings it down, and it stays down while it is alone ([GroupCall]
 * reads it so, with an estimate of 0, which stands under every mark). And beside video, sound lost past what its spare
 * copy hides is answered sooner ([HEAVY_HOLD_MS]): the video can give the room back.
 */
internal class CallMicCeiling(private val slowBelow: Long = SLOW_BELOW, private val backAt: Long = BACK_AT) {
    private var slow = false
    private var leavingSince: Long? = null

    val bitrate: Int
        get() = if (slow) SLOW_BITRATE else CallShapes.MICROPHONE_MAX_BITRATE

    /** Whether it has come down. */
    val lowered: Boolean
        get() = slow

    /**
     * One stats reading: the sender's estimate in bits per second, null when it gave none, and whether video went out
     * since the last. [downHoldMs] is how long a reading has to hold for the ceiling to come down on it, where that is
     * less than the hold either way. Answers whether the ceiling changed.
     */
    fun review(now: Long, estimate: Long?, videoSending: Boolean, downHoldMs: Long = HOLD_MS): Boolean {
        // A reading with no estimate says nothing, and doesn't restart the wait either.
        if (videoSending && estimate == null) return false

        val wanted = videoSending && (estimate ?: 0L) < (if (slow) backAt else slowBelow)

        if (wanted == slow) {
            leavingSince = null

            return false
        }

        val since = leavingSince ?: now

        leavingSince = since

        if (now - since < (if (wanted) minOf(downHoldMs, HOLD_MS) else HOLD_MS)) return false

        slow = wanted
        leavingSince = null

        return true
    }

    companion object {
        const val SLOW_BITRATE = 32_000
        const val SLOW_BELOW = 600_000L

        /** Above where it comes down, so the ceiling doesn't flap around one mark. */
        const val BACK_AT = 750_000L
        const val ROOM_SLOW_BELOW = 900_000L
        const val ROOM_BACK_AT = 1_000_000L
        const val HOLD_MS = 10_000L

        /** How long a room's microphone losing heavily beside video ([CallMicLoss.HEAVY_MARK]) has to go on before it comes down. */
        const val HEAVY_HOLD_MS = 4_000L
    }
}

/**
 * What a room's microphone hears of itself, at each reading of its ceiling, as the browsers' `watchRoomMic` reads it. A
 * camera whose viewers want little sends one layer and nothing holds it back, while the upload can still be too slow for
 * it and the microphone together. Then the share of the microphone its node says it lost, or asked for again, tells, and
 * realtime-client's calls/mic-ceiling.ts says what was measured.
 */
internal class CallMicLoss {
    /**
     * What a reading says of the microphone: whether enough of it went out to say anything, which is the person talking;
     * whether the share of that lost or asked for again says the upload can't carry it, and whether it is past what a
     * spare copy hides; and what the microphone put on the line since the last reading, in bits per second with its
     * packets' headers, null where that can't be said.
     */
    data class Heard(val talking: Boolean = false, val lossy: Boolean = false, val heavy: Boolean = false, val wire: Double? = null)

    private var sent: Long? = null
    private var missed: Long? = null
    private var bytes: Long? = null
    private var at: Long? = null
    private var videoPackets = 0L
    private var videoDelay = 0.0

    /** One reading of the video going out: its packets and how long they have waited so far. A reconnect's new senders count from nothing, which reads as none gone out. */
    fun queued(packets: Long, delay: Double): Boolean {
        val went = packets - videoPackets
        val waited = delay - videoDelay

        videoPackets = packets
        videoDelay = delay

        return went > 0 && waited / went >= QUEUED_MARK
    }

    /** One reading: what has gone out of the microphone so far and what its node says was lost or asked for again, null when there is no saying. */
    fun losing(sent: Long?, missed: Long?): Boolean = hearing(System.currentTimeMillis(), sent, missed, null).lossy

    /**
     * One reading, with its moment and what the sender counts of the microphone's bytes so far (its payload and its RTP
     * headers), null where it doesn't say: [losing], and the rest of what the reading tells.
     */
    fun hearing(now: Long, sent: Long?, missed: Long?, bytes: Long?): Heard {
        val sentBefore = this.sent
        val missedBefore = this.missed
        val bytesBefore = this.bytes
        val atBefore = at

        this.sent = sent
        this.missed = missed
        this.bytes = bytes
        at = now

        if (sent == null || missed == null || sentBefore == null || missedBefore == null) return Heard()

        // A reconnect's new sender counts from nothing, which reads as none gone out.
        val went = sent - sentBefore
        val talking = went >= MIN_PACKETS
        val share = if (talking) (missed - missedBefore).toDouble() / went else 0.0
        var wire: Double? = null

        if (went > 0 && bytes != null && bytesBefore != null && bytes >= bytesBefore && atBefore != null && now > atBefore) {
            wire = (bytes - bytesBefore + went * PACKET_OVERHEAD) * 8_000.0 / (now - atBefore)
        }

        return Heard(talking, share >= MARK, share >= HEAVY_MARK, wire)
    }

    companion object {
        /** The share lost or asked for again since the last reading that says the upload can't carry it. */
        const val MARK = 0.04

        /** The share lost that no spare copy hides: about one packet in eight, where two in a row go often enough to be heard. */
        const val HEAVY_MARK = 0.12

        /** What a microphone's packet costs beside what its sender counts of it: its IP and UDP headers and SRTP's tag, in bytes. */
        const val PACKET_OVERHEAD = 38L

        /** The packets that has to hold to say anything: a quiet microphone sends next to none, one talking 25 a second in 40 ms frames. */
        const val MIN_PACKETS = 50L

        /** How long a room's video waits in its sender's queue, on average since the last reading, that says the upload can't carry it, in seconds. */
        const val QUEUED_MARK = 0.1
    }
}
