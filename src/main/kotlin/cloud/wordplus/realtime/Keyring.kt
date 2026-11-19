package cloud.wordplus.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom

/**
 * The keys that open what the realtime servers carry, and seal what the app sends through them:
 * each `{label}:{64 hex}` as the app's server hands it out, never from the realtime server. A sealed value is
 * `{label}:1.{base64url(nonce + ciphertext)}`, XChaCha20-Poly1305 with the label as associated data.
 *
 * - `d:{day}` the day's key, for the directory and calls' people;
 * - `u:{id}:{generation}` the user's own, for what the app's server sends them;
 * - `ch:{room}:{generation}` a sealed room's.
 */
class Keyring {
    private val keys = HashMap<String, ByteArray>()
    private val random = SecureRandom()

    /** Keys as the app's server hands them out; the labels added. */
    @Synchronized
    fun add(entries: List<String>): List<String> {
        val added = ArrayList<String>()
        for (entry in entries) {
            val colon = entry.lastIndexOf(':')
            if (colon < 0) continue
            val label = entry.substring(0, colon)
            val key = entry.substring(colon + 1).takeIf { it.length == 64 }?.hexBytes() ?: continue
            if (!valid(label)) continue
            keys[label] = key
            added.add(label)
        }
        return added
    }

    @Synchronized
    fun has(label: String): Boolean = keys.containsKey(label)

    /**
     * The keys held whose label starts with `prefix`, as the app's server hands them out (`{label}:{64 hex}`): for a
     * module that keeps some across launches, as the calls keep the day's keys so a phone woken by a ring opens the
     * caller's name before anything connects (realtime-calls' SystemCalls).
     */
    @WordPlusModulesApi
    @Synchronized
    fun entries(prefix: String): List<String> =
        keys.filterKeys { it.startsWith(prefix) }.map { (label, key) -> label + ":" + key.joinToString("") { "%02x".format(it) } }.sorted()

    /** The newest key held of one thing, by its label up to the generation (`ch:chat:34:42:`, `u:42:`); null for none. */
    @Synchronized
    fun newest(prefix: String): String? {
        var best = 0
        for (label in keys.keys) {
            if (!label.startsWith(prefix)) continue
            val rest = label.substring(prefix.length)
            if (rest.isNotEmpty() && rest.all { it.isDigit() } && rest.length <= 9) best = maxOf(best, rest.toInt())
        }
        return if (best > 0) prefix + best else null
    }

    fun seal(text: String, label: String): String? {
        val key = key(label) ?: return null
        val nonce = ByteArray(24).also { random.nextBytes(it) }
        return label + ":1." + Base64Url.encode(nonce + XChaCha.seal(key, nonce, text.toByteArray(), label.toByteArray()))
    }

    /** JSON sealed with a label's key, its binary parts inside as `{ "$b": base64 }`. */
    fun sealJson(value: JsonElement, label: String): String? = seal(value.toString(), label)

    /** The plain text of a sealed value; a value not sealed comes back as it is; null when its key isn't held or it doesn't open. */
    fun open(value: String): String? {
        val match = SEALED.matchEntire(value) ?: return value
        val label = match.groupValues[1]
        val key = key(label) ?: return null
        val raw = Base64Url.decode(match.groupValues[2]) ?: return null
        if (raw.size < 40) return null
        return XChaCha.open(key, raw.copyOfRange(0, 24), raw.copyOfRange(24, raw.size), label.toByteArray())?.toString(Charsets.UTF_8)
    }

    /** A sealed value's JSON, parsed; a value not sealed as it is; null when it doesn't open. */
    fun openJson(value: JsonElement): JsonElement? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || labelOf(text) == null) return value
        val plain = open(text) ?: return null
        return try {
            Json.parseToJsonElement(plain)
        } catch (_: Exception) {
            null
        }
    }

    /** The labels a value needs that the keyring doesn't hold, however deep. */
    fun missing(value: JsonElement): List<String> {
        val out = LinkedHashSet<String>()
        fun visit(v: JsonElement, depth: Int) {
            if (depth > 8) return
            when (v) {
                is JsonPrimitive -> if (v.isString) labelOf(v.content)?.let { if (!has(it)) out.add(it) }
                is JsonArray -> v.forEach { visit(it, depth + 1) }
                is JsonObject -> v.values.forEach { visit(it, depth + 1) }
                JsonNull -> {}
            }
        }
        visit(value, 0)
        return out.toList()
    }

    @Synchronized
    private fun key(label: String): ByteArray? = keys[label]

    companion object {
        private const val LABEL = "[a-z][a-z0-9]{0,15}:[A-Za-z0-9_\\-=@,.;:]{1,240}"
        private val SEALED = Regex("^($LABEL):1\\.([A-Za-z0-9_-]+)$")
        private val LABEL_RE = Regex("^$LABEL$")

        /** The label of the key a sealed value needs, or null when it isn't sealed. */
        fun labelOf(value: String): String? = SEALED.matchEntire(value)?.groupValues?.get(1)

        fun valid(label: String): Boolean = label.length <= 256 && LABEL_RE.matches(label)
    }
}

/** A JSON value's text, when it is a string. */
internal val JsonElement?.text: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A user id as the realtime servers name them: a string, or an integer as text. */
internal val JsonElement?.id: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return p.content
        return p.contentOrNull?.let { c -> c.toDoubleOrNull()?.let { if (it == Math.floor(it)) it.toLong().toString() else c } }
    }

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Bytes kept in JSON as `{ "$b": base64 }`, as binary parts and the sealed rooms carry them here; null otherwise. */
fun JsonElement.bytes(): ByteArray? {
    val b = ((this as? JsonObject)?.takeIf { it.size == 1 }?.get("\$b") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return Base64Url.decodePlain(b)
}

/**
 * Bytes as JSON can carry them: `{ "$b": base64 }`. In an event they go as bytes, a binary attachment, as the browser's
 * and the Swift SDK's do, and sealed as binary in a sealed room.
 */
fun ByteArray.toJson(): JsonElement = JsonObject(mapOf("\$b" to JsonPrimitive(Base64Url.encodePlain(this))))
