package cloud.wordplus.realtime.calls

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** `people` and `may` through the calls route, with no server. How the questions and answers read is CallPeopleTest's. */
@RunWith(AndroidJUnit4::class)
class PeopleRouteTest {
    @Test
    fun peopleComeFromTheRoute() = runBlocking {
        val s = StandIn(route = { 200 to json("""{"people":[{"id":43,"name":"Cara"}]}""") })
        assertEquals(listOf(CallPerson("43", "Cara")), s.calls.people("support-42"))
        assertEquals(listOf(json("""{"people":"support-42"}""")), s.bodies.toList())
    }

    @Test
    fun noPeopleWhenTheRouteRefuses() = runBlocking {
        assertEquals(emptyList<CallPerson>(), StandIn(route = { 200 to json("""{"denied":"forbidden"}""") }).calls.people("x"))
        assertEquals(emptyList<CallPerson>(), StandIn(route = { 403 to null }).calls.people("x"))
        assertEquals(emptyList<CallPerson>(), StandIn(route = { 500 to null }).calls.people("x"))
    }

    @Test
    fun mayGoesAHundredARequest() = runBlocking {
        val s = StandIn(route = { body ->
            val count = (body["may"] as JsonArray).size
            200 to json("""{"may":[${List(count) { "true" }.joinToString(",")}]}""")
        })
        val may = s.calls.may(List(150) { CallAsk("support:{pair}", (100 + it).toString()) })
        assertEquals(List<Boolean?>(150) { true }, may)
        assertEquals(listOf(100, 50), s.bodies.map { (it["may"] as JsonArray).size })
    }

    @Test
    fun mayIsNullWhereTheRouteGaveNoAnswer() = runBlocking {
        val ask = listOf(CallAsk("support:42:old", "old"))
        assertEquals(listOf<Boolean?>(null), StandIn(route = { 200 to json("""{"ok":true}""") }).calls.may(ask))
        assertEquals(listOf<Boolean?>(null), StandIn(route = { 500 to null }).calls.may(ask))
        assertEquals(listOf<Boolean?>(null), StandIn(route = { 200 to json("""{"denied":"forbidden"}""") }).calls.may(ask))
    }

    @Test
    fun nothingToAskAsksNothing() = runBlocking {
        val s = StandIn()
        assertEquals(emptyList<Boolean?>(), s.calls.may(emptyList()))
        assertEquals(0, s.bodies.size)
    }
}
