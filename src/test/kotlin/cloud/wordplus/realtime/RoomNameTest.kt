package cloud.wordplus.realtime

import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A room of two people by a name with `{pair}`, as `rt.join( 'chat:{pair}', { with: 34 } )` is on the web: both of them
 * name the same room.
 */
class RoomNameTest {
    private val apps = mutableListOf<Realtime>()

    private fun app(user: String?): Realtime {
        val identity = user?.let {
            val claims = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$it"}""".toByteArray())
            Identity("x.$claims.y", System.currentTimeMillis() / 1000.0 + 3600)
        }
        val rt = Realtime(RealtimeOptions(app = "s1", endpoint = "http://127.0.0.1:9/api/realtime", identity = identity))
        rt.socketAsked = FakeSocket(rt)
        apps.add(rt)
        return rt
    }

    @AfterTest
    fun closed() {
        apps.forEach { it.close() }
    }

    @Test
    fun bothPeopleNameTheSameRoomTheSmallerIdFirst() {
        assertEquals("chat:34:42", app("42").join("chat:{pair}", with = "34").name)
        assertEquals("chat:34:42", app("34").join("chat:{pair}", with = "42").name)
        assertEquals("chat:9:10", app("9").join("chat:{pair}", with = "10").name)
        assertEquals("chat:user_a:user_b", app("user_b").join("chat:{pair}", with = "user_a").name)
    }

    @Test
    fun theRoomIsFoundAndLeftByTheSameWords() {
        val rt = app("42")
        val room = rt.join("chat:{pair}", with = "34")

        assertSame(room, rt.room("chat:{pair}", with = "34"))

        rt.leave("chat:{pair}", with = "34")
        Thread.sleep(50)
        assertNull(rt.room("chat:34:42"))
    }

    @Test
    fun anAppWithNoUserIsRefused() {
        assertEquals("no_identity", assertFailsWith<RealtimeException> { app(null).join("chat:{pair}", with = "34") }.code)
    }

    @Test
    fun anyOtherNameIsAsItIs() {
        assertEquals("chat:7", app(null).join("chat:7", with = "34").name)
    }
}
