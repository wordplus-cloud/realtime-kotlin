package cloud.wordplus.realtime.calls

import cloud.wordplus.realtime.WordPlusModulesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Someone's camera changing its codec in the middle of a room call, as the web's call screen does it: it says
 * `{"state":"optimizing"}` on the data channel ([TOPIC]), takes its camera down, puts it up again in the other codec and
 * says `{"state":"ready"}`, which it also says when a failed attempt has given up and left the old camera up. Between
 * the two the person has no camera, which drawn as it is would read as the camera turned off; a screen shows a
 * placeholder ("Optimizing video quality...") instead.
 *
 * The mark ([optimizing]) holds until, as the web's call screen holds it (camera-moves.ts, `OptimizingHold`):
 * - the first frame of a camera publication other than the one up when the word came ([from]), whose last frames still
 *   arriving must not end it;
 * - after `ready`, the next frame of whatever camera is up, the old one included: a move that changed nothing ends at
 *   once;
 * - the camera is turned off: muted, or taken out of the room other than by the move itself (the old one before
 *   `ready`);
 * - [failsafeMs] from the move's first word, whatever was heard since: the media server's `tune.failsafeS`, held to
 *   [FAILSAFE_MIN_MS]..[FAILSAFE_MAX_MS], [FAILSAFE_DEFAULT_MS] where it gives none.
 *
 * A move is over at its `ready` or its failsafe: an `optimizing` said again before then starts nothing new, so a peer
 * repeating itself can't keep the placeholder up. [GroupCall] keeps one per room and hears the frames itself, so a mark
 * that has ended stays ended whatever a screen's renderer does next; the SDK's call screens draw the placeholder while
 * it is up. [GroupPerson.optimizing] says it until a new camera of theirs arrives ([awaiting]).
 */
@WordPlusModulesApi
class CameraSwitch {
    data class Pending(
        /** The move's first word: its failsafe runs from it. */
        val since: Long,
        /** The camera publication up when the word came, null with none: its frames are not the new camera's. */
        val from: String?,
        /** `ready` heard: the next frame of any camera ends the mark. */
        val ready: Boolean,
        /**
         * The placeholder is up. Taken down before its `ready`, the move is kept until then, so no word of the same move
         * puts it up again.
         */
        val up: Boolean,
    )

    /** This room's: its node's `tune.failsafeS`, read as it joined ([failsafeFor]). */
    var failsafeMs = FAILSAFE_DEFAULT_MS

    private val pending = HashMap<String, Pending>()

    /** A word from [identity], whose camera publication is [camera] now. Whether their mark came up or went down. */
    fun heard(state: String, identity: String, camera: String?, now: Long): Boolean {
        val current = pending[identity]
        val live = current != null && now - current.since < failsafeMs

        when (state) {
            "optimizing" -> {
                // The same move said again: nothing new, its failsafe still running from its first word.
                if (current != null && live && !current.ready) return false

                pending[identity] = Pending(now, camera, ready = false, up = true)
                return current?.up != true
            }

            "ready" -> {
                current ?: return false

                // Over already: its mark taken down by a frame, or its failsafe passed. The move ends here.
                if (!live || !current.up) {
                    pending.remove(identity)
                    return current.up
                }

                pending[identity] = current.copy(ready = true)
                return false
            }

            else -> return false
        }
    }

    /** A frame of [identity]'s camera publication [camera] arrived. Whether it ended their mark ([watches]). */
    fun shown(identity: String, camera: String): Boolean {
        if (!watches(identity, camera)) return false

        end(identity)
        return true
    }

    /**
     * [identity]'s camera publication [camera] was taken out of the room. Whether that ended their mark: the old camera
     * going is the move itself until `ready`; any other, or after it, is the camera turned off.
     */
    fun unpublished(identity: String, camera: String): Boolean {
        val current = pending[identity] ?: return false

        if (!current.up || (!current.ready && camera == current.from)) return false

        end(identity)
        return true
    }

    /** [identity] muted their camera, which no move does: turned off. Whether that ended their mark. */
    fun muted(identity: String): Boolean {
        if (pending[identity]?.up != true) return false

        end(identity)
        return true
    }

    /** Marks past their failsafe, taken down, and their moves forgotten. Whose mark that took down. */
    fun expire(now: Long): List<String> {
        val over = pending.filter { now - it.value.since >= failsafeMs }

        over.keys.forEach { pending.remove(it) }

        return over.filter { it.value.up }.keys.sorted()
    }

    /** Gone from the room: nothing to wait for. */
    fun left(identity: String) {
        pending.remove(identity)
    }

    fun clear() {
        pending.clear()
        failsafeMs = FAILSAFE_DEFAULT_MS
    }

    /** Whether [identity]'s camera is held as changing now: a screen draws its placeholder. */
    fun optimizing(identity: String): Boolean = pending[identity]?.up == true

    /** The camera publication that was up when the word came. */
    fun from(identity: String): String? = pending[identity]?.from

    /**
     * Whether a frame of [identity]'s camera publication [camera] ends their mark: a camera other than the old one, or,
     * once `ready` is heard, any.
     */
    fun watches(identity: String, camera: String?): Boolean {
        val current = pending[identity] ?: return false

        if (camera == null || !current.up) return false

        return current.ready || camera != current.from
    }

    /** Whether anyone's mark is up: the room listens for frames only then. */
    fun anyUp(): Boolean = pending.values.any { it.up }

    /** When the next mark up reaches its failsafe, or null with none up. */
    fun nextExpiry(): Long? = pending.values.filter { it.up }.minOfOrNull { it.since }?.plus(failsafeMs)

    /**
     * What [GroupPerson.optimizing] says, with [camera] the camera publication of theirs that has arrived (null with
     * none): their mark is up and no camera other than the old one has come yet. A screen holds its placeholder on
     * until the room ends the mark.
     */
    fun awaiting(identity: String, camera: String?): Boolean =
        covers(optimizing(identity), from(identity), camera, camera != null)

    /** The mark down. The move stays until its `ready`, or goes now if that was heard. */
    private fun end(identity: String) {
        val current = pending[identity] ?: return

        if (current.ready) {
            pending.remove(identity)
        } else {
            pending[identity] = current.copy(up = false)
        }
    }

    companion object {
        const val TOPIC = "wp-camera"

        /**
         * The failsafe where the media server's codec policy gives none, and the range one it gives is held to: the
         * web's (the realtime client's CAMERA_TUNE_RANGES.failsafeS, the mesh's CodecTuneRange).
         */
        const val FAILSAFE_DEFAULT_MS = 10_000L
        const val FAILSAFE_MIN_MS = 3_000L
        const val FAILSAFE_MAX_MS = 30_000L

        /** The failsafe for a node's `tune.failsafeS`, in seconds: held to its range, the default where it gives none. */
        fun failsafeFor(seconds: Double?): Long {
            if (seconds == null || !seconds.isFinite()) return FAILSAFE_DEFAULT_MS

            return (seconds * 1000).toLong().coerceIn(FAILSAFE_MIN_MS, FAILSAFE_MAX_MS)
        }

        /**
         * The number [name] in a node's codec policy's `tune` (`Room.meshCodecPolicy`), null where it has none. Read by
         * name: the WordPlus RTC client for Android keeps `tune` from 1.0.6 on (the one these calls run), and a client
         * before it has none, so the same code reads null there and the default stands, whichever client an app takes.
         * consumer-rules.pro keeps the getter through an app's minifying.
         */
        fun tune(name: String, policy: Any?): Double? = try {
            val tune = policy?.javaClass?.getMethod("getTune")?.invoke(policy) as? Map<*, *>
            (tune?.get(name) as? Number)?.toDouble()
        } catch (e: Exception) {
            null
        }

        /**
         * The word, as the sender wrote it, or null for anything else: the whole message one JSON object, with a string
         * `state` at its top, as the web's JSON.parse reads it. Nothing truncated, nested or with more after it.
         */
        fun stateOf(data: ByteArray): String? {
            val message = try {
                Json.parseToJsonElement(data.decodeToString()) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            val state = (message["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content

            return state?.takeIf { it == "optimizing" || it == "ready" }
        }

        /** Whether a mark up still covers a camera: none, or the old one, whatever it draws, does not end it; another that draws does. */
        fun covers(optimizing: Boolean, from: String?, camera: String?, rendering: Boolean): Boolean {
            if (!optimizing) return false
            if (camera == null || !rendering) return true

            return camera == from
        }
    }
}
