package cloud.wordplus.realtime

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The routes of the app's server as its config() names them: beside each other, or each at an address of its own. */
class RoutesTest {
    private fun options(config: String) = RealtimeOptions.fromConfig(Json.parseToJsonElement(config), "https://shop.example.com/")

    @Test
    fun routesBesideEachOtherNeedOnlyTheEndpoint() {
        val options = options("""{"site":"s1","auth":{"endpoint":"/api/realtime/auth"},"calls":{"endpoint":"/api/realtime/calls"},"keys":{"endpoint":"/api/realtime/keys"}}""")
        assertEquals("https://shop.example.com/api/realtime", options.endpoint)
        assertEquals(emptyMap(), options.routes)
    }

    @Test
    fun aRouteAtAnAddressOfItsOwnIsKeptInFull() {
        val options = options("""{"site":"s1","auth":{"endpoint":"/api/realtime/auth"},"calls":{"endpoint":"/check-call"},"keys":{"endpoint":"https://api.example.com/my/keys"}}""")
        assertEquals("https://shop.example.com/api/realtime", options.endpoint)
        assertEquals(mapOf("calls" to "https://shop.example.com/check-call", "keys" to "https://api.example.com/my/keys"), options.routes)
    }
}
