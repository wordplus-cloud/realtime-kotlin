package cloud.wordplus.realtime.calls

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The seal on every signal a direct call exchanges.
 *
 * WordPress hands both sides a key in the `callCreate` / `joinCall` replies.
 * The realtime server relays and stores the sealed bodies and never holds that
 * key, so the offer, the answer, the candidates and everything the two sides
 * say about their cameras stay between them.
 *
 * AES-256-GCM under SHA-256 of the key, one random nonce per signal. Java's
 * GCM output already ends with the tag, which is the layout the web reads.
 */
internal class CallCipher private constructor(private val key: SecretKeySpec) {
    companion object {
        /**
         * The label and the key are joined by a NUL byte, which is what the
         * web hashes. A space or an empty string here derives a different key
         * and every signal silently fails to open.
         */
        private const val LABEL = "better-messages-p2p-signal\u0000"
        private const val TAG_BITS = 128

        fun create(secret: String): CallCipher? {
            if (secret.isEmpty()) return null

            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest((LABEL + secret).toByteArray(StandardCharsets.UTF_8))

                CallCipher(SecretKeySpec(digest, "AES"))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * True for a body that arrived sealed. A plain one is still accepted -
         * an older build on the other side seals nothing.
         */
        fun isSealed(body: JSONObject?): Boolean {
            if (body == null) return false

            return body.optInt("sealed", 0) == 1 &&
                body.opt("iv") is String &&
                body.opt("data") is String
        }
    }

    private val random = SecureRandom()

    fun seal(body: JSONObject): JSONObject? {
        return try {
            val iv = ByteArray(12)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

            val sealed = cipher.doFinal(body.toString().toByteArray(StandardCharsets.UTF_8))

            JSONObject()
                .put("sealed", 1)
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(sealed, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    fun open(body: JSONObject): JSONObject? {
        return try {
            val iv = Base64.decode(body.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(body.getString("data"), Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

            val plain = JSONObject(String(cipher.doFinal(data), StandardCharsets.UTF_8))

            if (plain.optString("kind").isEmpty()) null else plain
        } catch (e: Exception) {
            // A body that will not open is dropped rather than guessed at.
            null
        }
    }
}
