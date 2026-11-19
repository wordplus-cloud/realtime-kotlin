package cloud.wordplus.realtime

import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * XChaCha20-Poly1305, as libsodium's `crypto_aead_xchacha20poly1305_ietf` and the browser's @noble/ciphers make it,
 * in plain Kotlin so it runs on every Android version: HChaCha20 turns the key and the first 16 bytes of the 24-byte
 * nonce into a subkey, and ChaCha20-Poly1305 (RFC 8439) seals with it and the nonce's last 8 bytes. The result is the
 * ciphertext followed by its 16-byte tag.
 */
internal object XChaCha {
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 24)
        val subkey = hChaCha(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(4) + nonce.copyOfRange(16, 24)
        val cipher = chaCha(subkey, nonce12, 1, plain)
        return cipher + tag(subkey, nonce12, aad, cipher)
    }

    /** The plain bytes, or null when the value was changed or sealed with another key or label. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 24 || sealed.size < 16) return null
        val subkey = hChaCha(key, nonce.copyOfRange(0, 16))
        val nonce12 = ByteArray(4) + nonce.copyOfRange(16, 24)
        val cipher = sealed.copyOfRange(0, sealed.size - 16)
        val given = sealed.copyOfRange(sealed.size - 16, sealed.size)
        if (!constantTimeEquals(given, tag(subkey, nonce12, aad, cipher))) return null
        return chaCha(subkey, nonce12, 1, cipher)
    }

    private fun tag(key: ByteArray, nonce12: ByteArray, aad: ByteArray, cipher: ByteArray): ByteArray {
        val polyKey = block(key, nonce12, 0).copyOfRange(0, 32)
        val mac = java.io.ByteArrayOutputStream()
        mac.write(aad); mac.write(ByteArray((16 - aad.size % 16) % 16))
        mac.write(cipher); mac.write(ByteArray((16 - cipher.size % 16) % 16))
        mac.write(le64(aad.size.toLong())); mac.write(le64(cipher.size.toLong()))
        return poly1305(polyKey, mac.toByteArray())
    }

    /** HChaCha20: ChaCha20's 20 rounds over the key and 16 bytes of nonce, without the final addition, words 0–3 and 12–15. */
    fun hChaCha(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = word(key, 4 * i)
        for (i in 0 until 4) s[12 + i] = word(nonce16, 4 * i)
        rounds(s)
        val out = ByteArray(32)
        intArrayOf(0, 1, 2, 3, 12, 13, 14, 15).forEachIndexed { j, i -> put(out, 4 * j, s[i]) }
        return out
    }

    private fun chaCha(key: ByteArray, nonce12: ByteArray, counter: Int, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var at = 0
        var n = counter
        while (at < input.size) {
            val stream = block(key, nonce12, n++)
            val take = minOf(64, input.size - at)
            for (i in 0 until take) out[at + i] = (input[at + i].toInt() xor stream[i].toInt()).toByte()
            at += take
        }
        return out
    }

    private fun block(key: ByteArray, nonce12: ByteArray, counter: Int): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = word(key, 4 * i)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = word(nonce12, 4 * i)
        val w = s.copyOf()
        rounds(w)
        val out = ByteArray(64)
        for (i in 0 until 16) put(out, 4 * i, w[i] + s[i])
        return out
    }

    private fun rounds(s: IntArray) {
        fun q(a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
        }
        repeat(10) {
            q(0, 4, 8, 12); q(1, 5, 9, 13); q(2, 6, 10, 14); q(3, 7, 11, 15)
            q(0, 5, 10, 15); q(1, 6, 11, 12); q(2, 7, 8, 13); q(3, 4, 9, 14)
        }
    }

    private val P = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val MOD128 = BigInteger.ONE.shiftLeft(128)

    private fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val r = le(key.copyOfRange(0, 16)).and(CLAMP)
        val s = le(key.copyOfRange(16, 32))
        var acc = BigInteger.ZERO
        var at = 0
        while (at < message.size) {
            val take = minOf(16, message.size - at)
            val chunk = message.copyOfRange(at, at + take) + byteArrayOf(1)
            acc = acc.add(le(chunk)).multiply(r).mod(P)
            at += take
        }
        val tag = acc.add(s).mod(MOD128)
        val bytes = tag.toByteArray().reversedArray()
        return ByteArray(16) { if (it < bytes.size) bytes[it] else 0 }
    }

    private fun le(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun le64(n: Long): ByteArray = ByteArray(8) { (n ushr (8 * it)).toByte() }

    private fun word(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun put(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte(); out[at + 2] = (v ushr 16).toByte(); out[at + 3] = (v ushr 24).toByte()
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Base64url without padding, as the sealed values and the tokens use it, and plain base64 for `{ "$b" }`: Kotlin's
 * own, since java.util.Base64 came to Android only with API 26.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Url {
    private val url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val plain = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    fun decode(text: String): ByteArray? = try {
        url.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encodePlain(bytes: ByteArray): String = Base64.Default.encode(bytes)
    fun decodePlain(text: String): ByteArray? = try {
        plain.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun String.hexBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}
