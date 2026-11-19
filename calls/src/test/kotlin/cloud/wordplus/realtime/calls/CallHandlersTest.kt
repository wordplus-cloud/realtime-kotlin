package cloud.wordplus.realtime.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** Handlers taken off again (`off`), as the browser's calls have them (realtime-client calls.ts). Run on the JVM. */
class CallHandlersTest {
    @Test
    fun handlersRunInTheOrderTheyCameAndOneTakenOffHearsNoMore() {
        val handlers = CallHandlers<Int>()
        val heard = ArrayList<String>()
        handlers.add({ heard.add("a$it") })
        val b = handlers.add({ heard.add("b$it") })
        handlers.add({ heard.add("c$it") })
        handlers.fire(1)
        handlers.remove(b)
        handlers.fire(2)
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2"), heard)
    }

    @Test
    fun aHandlerMayTakeItselfOffAsItRuns() {
        val handlers = CallHandlers<Unit>()
        var heard = 0
        lateinit var mine: CallListener
        mine = handlers.add({
            heard++
            handlers.remove(mine)
        })
        handlers.fire()
        handlers.fire()
        assertEquals(1, heard)
    }
}
