package cloud.wordplus.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conformance vectors every package checks itself against (@wordplus/realtime's spec/vectors.json, written by PHP
 * with libsodium): a value sealed here opens there, and the other way round.
 */
class VectorsTest {
    private val v = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText()).jsonObject
    private val entries = v["keys"]!!.jsonArray.map { it.jsonObject["entry"]!!.jsonPrimitive.content }

    @Test
    fun opensWhatPhpSealedAndSealsAsLibsodium() {
        val ring = Keyring()
        ring.add(entries)
        for (case in v["sealed"]!!.jsonArray.map { it.jsonObject }) {
            val label = case["label"]!!.jsonPrimitive.content
            val plain = case["plain"]!!.jsonPrimitive.content
            val sealed = case["sealed"]!!.jsonPrimitive.content
            assertEquals(plain, ring.open(sealed), label)

            val nonce = case["nonce_hex"]!!.jsonPrimitive.content.hexBytes()!!
            val key = entries.first { it.startsWith("$label:") && it.length == label.length + 65 }.takeLast(64).hexBytes()!!
            assertEquals(sealed, label + ":1." + Base64Url.encode(nonce + XChaCha.seal(key, nonce, plain.toByteArray(), label.toByteArray())), label)
        }
    }

    @Test
    fun refusesAValueMovedOrChangedAndKeepsKeysTheAppGave() {
        val ring = Keyring()
        ring.add(entries)
        val first = v["sealed"]!!.jsonArray[0].jsonObject
        val sealed = first["sealed"]!!.jsonPrimitive.content
        val label = first["label"]!!.jsonPrimitive.content

        assertNull(ring.open(sealed.replace(label, "ch:order:43:1")))
        assertNull(ring.open(sealed.dropLast(2) + if (sealed.endsWith("A")) "BB" else "AA"))
        assertNull(Keyring().open(sealed))
        assertEquals("plain text", ring.open("plain text"))

        assertTrue(ring.has("ch:chat:34:42:2"))
        assertTrue(ring.has("u:k7f3q:ben:1"))
        assertEquals("ch:chat:34:42:2", ring.newest("ch:chat:34:42:"))
        assertEquals(emptyList(), ring.add(listOf("bad label:" + "a".repeat(64), "u:1:1:short")))
    }

    @Test
    fun sealsJsonWithBinaryInsideAndOpensItBack() {
        val ring = Keyring()
        ring.add(listOf("ch:files:1:" + "ab".repeat(32)))
        val value = JsonObject(mapOf("name" to JsonPrimitive("a.bin"), "bytes" to byteArrayOf(1, 2, -2).toJson()))
        val sealed = ring.sealJson(value, "ch:files:1")!!
        assertTrue(sealed.startsWith("ch:files:1:1."))
        val opened = ring.openJson(JsonPrimitive(sealed))!!
        assertEquals(value, opened)
        assertContentEquals(byteArrayOf(1, 2, -2), opened["bytes"]!!.bytes())
        assertEquals(listOf("d:1"), ring.missing(JsonObject(mapOf("a" to JsonPrimitive(sealed), "b" to JsonPrimitive("d:1:1.abc")))))
    }

    @Test
    fun hChaCha20MatchesTheXChaChaDraft() {
        // draft-irtf-cfrg-xchacha, 2.2.1. The whole of XChaCha20-Poly1305 is pinned to libsodium by the vectors above.
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexBytes()!!
        val nonce = "000000090000004a0000000031415927".hexBytes()!!
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", XChaCha.hChaCha(key, nonce).joinToString("") { "%02x".format(it) })
    }
}
