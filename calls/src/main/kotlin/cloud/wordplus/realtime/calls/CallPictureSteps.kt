package cloud.wordplus.realtime.calls

/**
 * The picture giving way to the sound, a step at a time, on an upload that can't carry both. The browsers' engine does
 * the same with the same numbers, and says why and what was measured (realtime-client, calls/picture-steps.ts).
 *
 * The sound comes first while the person talks. The microphone's ceiling gives the camera room; this is the other way
 * round, for the upload where the microphone has come down and is still being lost, or still waits behind the video in
 * the sender's queue: the camera is made smaller until the sound gets through, and never taken away. A step is a ceiling
 * on what the camera sends (the room client's product caps over its cameras), under whatever else holds it.
 *
 * - Down: only on a line the sender's estimate says is too narrow for even the first step beside what the microphone
 *   puts on the wire, after [DOWN_MS] of the sound suffering, straight to the first step that fits, and no further while
 *   the estimate says the line carries it. A line that loses at random loses the sound whatever the picture's size, and
 *   an overloaded one brings its estimate down. With the microphone, in the same reading, when it comes down for heavy
 *   loss ([giveWay]). One step at a time where there is no estimate.
 * - Up: after [UP_MS] of clean sound while the person talks. Silence says nothing of the line, so the picture stays
 *   where it is through it. Each step up is on trial for [RELAPSE_MS]: the first reading that says it cost the sound
 *   takes it back and doubles the wait for the next, up to [UP_MAX_MS]. A capped sender's estimate stays near one and a
 *   half times what it sends whatever the line carries, so it can't say beforehand whether a step up fits.
 *
 * An app reads its statistics less often than a browser does, so a step down waits one reading, whose loss is all from
 * after the step before.
 */
internal class CallPictureSteps {
    /** A ceiling on what a camera sends: its height in lines, its frames a second, its bitrate in kbps. */
    data class Caps(val height: Int, val fps: Int, val kbps: Int)

    /** What a reading says of the sound: it suffers, it is clean while the person talks, or it says nothing (silence, no camera going out, trouble the microphone hasn't answered yet). */
    enum class Reading { SUFFERING, CLEAN, IDLE }

    private var at = 0

    /** The readings in a row that said the same, and since when; an idle one breaks neither. */
    private var said: Reading? = null
    private var since = 0L
    private var upWait = UP_MS
    private var liftedAt: Long? = null

    /** How many steps down the picture is: 0 all of it. */
    val step: Int
        get() = at

    /** How long the next step up waits for, ms. */
    val wait: Long
        get() = upWait

    /**
     * The microphone has just come down for sound lost past what its spare copy hides. Where the line is said not to
     * carry even the picture's first step beside it ([fitting] 2 or more), the picture goes with it, to that step,
     * without a step's wait. Answers whether the step changed.
     */
    fun giveWay(now: Long, fitting: Int): Boolean {
        val to = minOf(fitting, CAMERA_STEPS.size)

        if (fitting < 2 || at >= to) return false

        at = to
        liftedAt = null
        said = Reading.SUFFERING
        since = now

        return true
    }

    /**
     * One reading. [fitting] is the first step the line is said to carry, 0 when nothing says: a step down goes straight
     * there, and no further while that is said; said to carry the first step, the picture takes none. Answers whether
     * the step changed.
     */
    fun review(now: Long, reading: Reading, fitting: Int = 0): Boolean {
        if (reading == Reading.IDLE) return false

        if (reading != said) {
            said = reading
            since = now
        }

        val lifted = liftedAt

        if (reading == Reading.SUFFERING) {
            if (at >= CAMERA_STEPS.size) return false

            // A step up on trial is taken back by the first reading that says it cost the sound.
            val onTrial = lifted != null && now - lifted <= RELAPSE_MS

            // The line is said to carry this step beside the microphone, or the whole picture's first step from none
            // taken: what loses the sound isn't the picture, and a smaller one would cost it for nothing.
            if (!onTrial && fitting > 0 && (at >= fitting || fitting < 2)) return false

            if (!onTrial && now - since < DOWN_MS) return false

            if (lifted != null && now - lifted <= RELAPSE_MS + DOWN_MS) upWait = minOf(upWait * 2, UP_MAX_MS)

            liftedAt = null
            // Taken back, it goes back the one step it came up; otherwise down to the first step said to fit.
            at = if (onTrial) at + 1 else minOf(maxOf(at + 1, fitting), CAMERA_STEPS.size)
            since = now

            return true
        }

        // A step up that held: the next line that jams starts from the first wait again.
        if (at == 0) {
            if (lifted != null && now - lifted > RELAPSE_MS + DOWN_MS) {
                liftedAt = null
                upWait = UP_MS
            }

            return false
        }

        if (now - since < upWait) return false

        at -= 1
        liftedAt = now
        since = now

        return true
    }

    companion object {
        /** A camera's steps down. */
        val CAMERA_STEPS = listOf(Caps(360, 15, 150), Caps(180, 10, 50), Caps(180, 8, 30))

        /** How long the sound has to go on suffering before the picture takes a step, or the next one. */
        const val DOWN_MS = 5_000L

        /** How long it has to be clean, the person talking, before a step back up. */
        const val UP_MS = 30_000L

        /** A step up that brought the trouble back within this of it is taken back at once, and doubles the wait for the next. */
        const val RELAPSE_MS = 30_000L

        /** The longest such a wait gets. */
        const val UP_MAX_MS = 300_000L

        /** The share of the sender's estimate a step's picture and the microphone may plan on: the rest is what a video sends beside its picture, again and as padding. */
        const val FIT = 0.85

        /** The ceiling a step puts on a camera: null at step 0. */
        fun caps(step: Int): Caps? = if (step <= 0) null else CAMERA_STEPS[minOf(step, CAMERA_STEPS.size) - 1]

        /**
         * The first step down at which a camera fits in [kbps] beside the microphone: the last step when none does, 0
         * when there is no figure to go by or no camera going out.
         */
        fun fitting(camera: Boolean, kbps: Double?): Int {
            if (!camera || kbps == null || !kbps.isFinite()) return 0

            for (step in 1..CAMERA_STEPS.size) if (CAMERA_STEPS[step - 1].kbps <= kbps) return step

            return CAMERA_STEPS.size
        }
    }
}
