package cloud.wordplus.realtime.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who this app's user may add, and whom they may call, through the app's calls route, as the browser's ask them
 * (realtime-client calls.ts:273, 288, its test/sdk-calls.test.ts): the questions and how their answers read
 * ([CallsRoute]). The Swift package's CallPeopleTests' twin, which goes through the route: PeopleRouteTest does so here,
 * on the emulator. Run on the JVM: `./gradlew testDebugUnitTest` in calls/.
 */
class CallPeopleTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun answer(text: String) = json(text) as JsonObject

    @Test
    fun peopleAreAskedByTheName() {
        assertEquals(json("""{"people":"support-42"}"""), CallsRoute.people("support-42"))
    }

    @Test
    fun anAnswerListsThePeopleWithAValidIdAndName() {
        val people = CallsRoute.people(answer("""{"people":[
            {"id":43,"name":"Cara","avatar":"https://shop.example/cara.svg"},
            {"id":"auth0|5f7c8e","name":"Dan"},
            {"id":"44","name":"Eve"},
            {"id":"two words","name":"broken"},
            {"id":0,"name":"nobody"},
            {"name":"no id"},
            {"id":45},
            {"id":46,"name":7},
            "not a person"
        ]}"""))
        assertEquals(listOf(
            CallPerson("43", "Cara", "https://shop.example/cara.svg"),
            CallPerson("auth0|5f7c8e", "Dan"),
            CallPerson("44", "Eve"),
        ), people)
    }

    @Test
    fun anAnswerWithNoPeopleHasNone() {
        assertEquals(emptyList<CallPerson>(), CallsRoute.people(answer("""{"ok":true}""")))
        assertEquals(emptyList<CallPerson>(), CallsRoute.people(answer("""{"people":"nobody"}""")))
    }

    @Test
    fun mayAsksEachCallAndPerson() {
        assertEquals(
            json("""{"may":[{"call":"support:42:43","to":43},{"call":"support:42:x","to":"auth0|5f7c8e"}]}"""),
            CallsRoute.may(listOf(CallAsk("support:42:43", "43"), CallAsk("support:42:x", "auth0|5f7c8e"))),
        )
    }

    @Test
    fun onlyTrueIsYes() {
        assertEquals(listOf<Boolean?>(true, false), CallsRoute.mayAnswer(answer("""{"may":[true,false]}"""), 2))
        assertEquals(listOf<Boolean?>(false, false, false), CallsRoute.mayAnswer(answer("""{"may":[1,"yes",null]}"""), 3))
    }

    @Test
    fun noAnswerKnowsNothing() {
        assertEquals(listOf<Boolean?>(null), CallsRoute.mayAnswer(null, 1))
        assertEquals(listOf<Boolean?>(null), CallsRoute.mayAnswer(answer("""{"ok":true}"""), 1))
        // The wrong number of answers.
        assertEquals(listOf<Boolean?>(null), CallsRoute.mayAnswer(answer("""{"may":[true,true]}"""), 1))
    }

    @Test
    fun theWaitAfterBeingOutIsIntoTheNextSecondAndALittleMore() {
        assertEquals(750L, CallsRoute.outWaitMs(1_000_400, 1_000_400))
        assertEquals(0L, CallsRoute.outWaitMs(1_000_400, 1_001_150))
        assertEquals(-850L, CallsRoute.outWaitMs(1_000_400, 1_002_000))
    }

    @Test
    fun aUserReadsAsTheBrowserReadsThem() {
        assertEquals("42", CallsRoute.user(JsonPrimitive(42)))
        assertEquals("42", CallsRoute.user(JsonPrimitive("42")))
        assertEquals("auth0|5f7c", CallsRoute.user(JsonPrimitive("auth0|5f7c")))
        assertEquals("a".repeat(64), CallsRoute.user(JsonPrimitive("a".repeat(64))))
        assertNull(CallsRoute.user(JsonPrimitive(0)))
        assertNull(CallsRoute.user(JsonPrimitive(1.5)))
        assertNull(CallsRoute.user(JsonPrimitive(true)))
        assertNull(CallsRoute.user(JsonPrimitive("0")))
        assertNull(CallsRoute.user(JsonPrimitive("")))
        assertNull(CallsRoute.user(JsonPrimitive("a b")))
        assertNull(CallsRoute.user(JsonPrimitive("a\u007f")))
        assertNull(CallsRoute.user(JsonPrimitive("a".repeat(65))))
        assertNull(CallsRoute.user(JsonNull))
        assertNull(CallsRoute.user(null))
    }
}
