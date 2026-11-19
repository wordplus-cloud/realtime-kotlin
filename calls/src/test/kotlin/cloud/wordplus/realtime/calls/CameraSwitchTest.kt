package cloud.wordplus.realtime.calls

import io.livekit.android.room.MeshCodecPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camera changing codec ([CameraSwitch]), as the web's call screen holds it: up from the move's first word, the old
 * camera's frames and going don't end it, a new camera's first frame does, after `ready` any camera's next frame does,
 * the camera turned off does, and the media server's failsafe does, counted from the first word. [GroupPerson.optimizing]
 * says it until a new camera arrives.
 */
class CameraSwitchTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun readsTheWordAsJson() {
        assertEquals("wp-camera", CameraSwitch.TOPIC)
        assertEquals("optimizing", CameraSwitch.stateOf(bytes("""{"state":"optimizing"}""")))
        assertEquals("ready", CameraSwitch.stateOf(bytes("""{ "state" : "ready" }""")))
        assertEquals("ready", CameraSwitch.stateOf(bytes("""{"at":1,"state":"ready"} """)))
        assertNull(CameraSwitch.stateOf(bytes("""{"state":"off"}""")))
        // the bye topic's payload, or anything else on the channel
        assertNull(CameraSwitch.stateOf(bytes("1")))
        assertNull(CameraSwitch.stateOf(bytes("")))
        // what the old pattern took: cut short, nested, not at the top, not a string, more after it
        assertNull("truncated", CameraSwitch.stateOf(bytes("""{"state":"ready"""")))
        assertNull("nested", CameraSwitch.stateOf(bytes("""{"camera":{"state":"ready"}}""")))
        assertNull("in a list", CameraSwitch.stateOf(bytes("""[{"state":"ready"}]""")))
        assertNull("not a string", CameraSwitch.stateOf(bytes("""{"state":["ready"]}""")))
        assertNull("more after it", CameraSwitch.stateOf(bytes("""{"state":"ready"}{"state":"optimizing"}""")))
    }

    /** A codec policy as the WordPlus RTC client for Android has it from 1.0.6 on, and as 1.0.5 has it. */
    private data class PolicyWithTune(val camera: List<String>? = null, val tune: Map<String, Double>? = null)
    private data class PolicyBefore(val camera: List<String>? = null)

    @Test
    fun theFailsafeIsTheNodesHeldToItsRange() {
        assertEquals(10_000L, CameraSwitch.FAILSAFE_DEFAULT_MS)
        assertEquals(10_000L, CameraSwitch.failsafeFor(null))
        assertEquals(10_000L, CameraSwitch.failsafeFor(Double.NaN))
        assertEquals(4_500L, CameraSwitch.failsafeFor(4.5))
        assertEquals(3_000L, CameraSwitch.failsafeFor(1.0))
        assertEquals(30_000L, CameraSwitch.failsafeFor(120.0))

        assertEquals(4.5, CameraSwitch.tune("failsafeS", PolicyWithTune(tune = mapOf("failsafeS" to 4.5)))!!, 0.0)
        assertNull(CameraSwitch.tune("failsafeS", PolicyWithTune(tune = mapOf("checkMs" to 3000.0))))
        assertNull(CameraSwitch.tune("failsafeS", PolicyWithTune()))
        assertNull("a client with no tune: the default", CameraSwitch.tune("failsafeS", PolicyBefore(listOf("vp8"))))
        assertNull(CameraSwitch.tune("failsafeS", null))
    }

    /**
     * The policy as the room client hands it over (`Room.meshCodecPolicy`, the WordPlus RTC client for Android 1.0.6):
     * its `tune` is what the failsafe is read from, and a policy without one leaves the default.
     */
    @Test
    fun theRoomClientsOwnPolicyGivesTheFailsafe() {
        val given = MeshCodecPolicy(camera = listOf("vp9", "vp8"), check = true, adapt = true, tune = mapOf("failsafeS" to 4.5, "checkMs" to 3000.0))
        assertEquals(4.5, CameraSwitch.tune("failsafeS", given)!!, 0.0)
        assertEquals(4_500L, CameraSwitch.failsafeFor(CameraSwitch.tune("failsafeS", given)))
        assertEquals(30_000L, CameraSwitch.failsafeFor(CameraSwitch.tune("failsafeS", MeshCodecPolicy(tune = mapOf("failsafeS" to 90.0)))))
        assertEquals(10_000L, CameraSwitch.failsafeFor(CameraSwitch.tune("failsafeS", MeshCodecPolicy(camera = listOf("vp8")))))
        val none: MeshCodecPolicy? = null
        assertEquals(10_000L, CameraSwitch.failsafeFor(CameraSwitch.tune("failsafeS", none)))
    }

    @Test
    fun upUntilTheNewCamerasFirstFrame() {
        val s = CameraSwitch()

        assertTrue("the word changes what is shown", s.heard("optimizing", "user_9", "TR_old", 0))
        assertTrue(s.optimizing("user_9"))
        assertTrue(s.anyUp())

        // the old camera's frames and its going are the move: nothing ends
        assertFalse(s.watches("user_9", "TR_old"))
        assertFalse(s.shown("user_9", "TR_old"))
        assertFalse(s.unpublished("user_9", "TR_old"))
        assertTrue(s.optimizing("user_9"))

        // the new camera's first frame does, `ready` or not, and nothing brings it back
        assertTrue(s.watches("user_9", "TR_new"))
        assertTrue(s.shown("user_9", "TR_new"))
        assertFalse(s.optimizing("user_9"))
        assertNull(s.nextExpiry())
        assertFalse("a later frame changes nothing", s.shown("user_9", "TR_other"))
        assertFalse("its ready closes the move", s.heard("ready", "user_9", "TR_new", 1_500))
        assertFalse(s.optimizing("user_9"))
    }

    @Test
    fun aReadyEndsItAtTheNextFrameOfAnyCamera() {
        val s = CameraSwitch()

        s.heard("optimizing", "user_9", "TR_old", 0)
        assertFalse("ready before the new camera arrived", s.heard("ready", "user_9", null, 1_000))
        assertTrue(s.optimizing("user_9"))
        assertTrue(s.shown("user_9", "TR_new"))
        assertFalse(s.optimizing("user_9"))

        // a move that changed nothing: the old camera's next frame after `ready`
        s.heard("optimizing", "user_5", "TR_same", 0)
        assertFalse(s.shown("user_5", "TR_same"))
        s.heard("ready", "user_5", "TR_same", 900)
        assertTrue(s.shown("user_5", "TR_same"))
        assertFalse(s.optimizing("user_5"))
    }

    @Test
    fun aCameraTurnedOffEndsIt() {
        val s = CameraSwitch()

        s.heard("optimizing", "user_1", "TR_a", 0)
        assertTrue("muted: no move mutes", s.muted("user_1"))
        assertFalse(s.optimizing("user_1"))

        s.heard("optimizing", "user_2", "TR_a", 0)
        assertTrue("the new camera taken out again", s.unpublished("user_2", "TR_b"))
        assertFalse(s.optimizing("user_2"))

        s.heard("optimizing", "user_3", "TR_a", 0)
        s.heard("ready", "user_3", "TR_a", 800)
        assertTrue("the old camera taken out after a ready that left it up", s.unpublished("user_3", "TR_a"))
        assertFalse(s.optimizing("user_3"))

        assertFalse(s.muted("user_4"))
        assertFalse(s.unpublished("user_4", "TR_a"))
    }

    @Test
    fun thePeoplesModelSaysItUntilANewCameraArrives() {
        val s = CameraSwitch()

        assertFalse("nothing said, nothing up", s.awaiting("user_9", "TR_old"))
        s.heard("optimizing", "user_9", "TR_old", 0)
        assertTrue("the old camera still here", s.awaiting("user_9", "TR_old"))
        assertTrue("no camera in between: away, not off", s.awaiting("user_9", null))
        assertFalse("a new camera has arrived", s.awaiting("user_9", "TR_new"))
        // a screen holds on past that, to the frame that ends it
        assertTrue(s.optimizing("user_9"))
    }

    @Test
    fun theFailsafeRunsFromTheMovesFirstWord() {
        val s = CameraSwitch()
        s.failsafeMs = CameraSwitch.failsafeFor(4.0)

        s.heard("optimizing", "user_3", "TR_a", 0)
        assertEquals(4_000L, s.nextExpiry())

        // said again: nothing new, its first word's camera and time kept
        assertFalse(s.heard("optimizing", "user_3", null, 3_000))
        assertEquals("TR_a", s.from("user_3"))
        assertEquals(4_000L, s.nextExpiry())
        assertTrue(s.expire(3_999).isEmpty())
        assertEquals(listOf("user_3"), s.expire(4_000))
        assertFalse(s.optimizing("user_3"))

        // a peer repeating itself can't keep it up, nor put up again a move a frame ended
        s.heard("optimizing", "user_7", "TR_a", 10_000)
        for (at in 11_000L..13_000L step 1_000L) assertFalse(s.heard("optimizing", "user_7", "TR_a", at))
        assertEquals(listOf("user_7"), s.expire(14_000))
        s.heard("optimizing", "user_8", "TR_a", 20_000)
        s.shown("user_8", "TR_b")
        assertFalse(s.heard("optimizing", "user_8", "TR_b", 21_000))
        assertFalse(s.optimizing("user_8"))

        // a new move after its `ready`
        s.heard("ready", "user_8", "TR_b", 22_000)
        assertTrue(s.heard("optimizing", "user_8", "TR_b", 23_000))
        assertEquals(27_000L, s.nextExpiry())
    }

    @Test
    fun readyWithNothingUpAndLeaving() {
        val s = CameraSwitch()

        assertFalse(s.heard("ready", "user_5", "TR_x", 0))
        assertFalse(s.optimizing("user_5"))

        s.heard("optimizing", "user_6", "TR_a", 0)
        s.left("user_6")
        assertFalse(s.optimizing("user_6"))

        s.heard("optimizing", "user_2", null, 0)
        assertTrue("with no old camera any camera's frame ends it", s.shown("user_2", "TR_any"))

        s.failsafeMs = 4_000L
        s.heard("optimizing", "user_7", "TR_a", 0)
        s.clear()
        assertFalse(s.anyUp())
        assertEquals(CameraSwitch.FAILSAFE_DEFAULT_MS, s.failsafeMs)
    }

    @Test
    fun someoneNotChangingIsNotOptimizing() {
        val person = GroupPerson(
            identity = "user_1", user = "1", info = kotlinx.serialization.json.JsonNull,
            camera = true, microphone = true, speaking = false,
        )
        assertFalse("the field defaults to false", person.optimizing)
    }
}
