package br.com.prodatastelecom.zapapi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ChatSummary(
    val id: String,
    val name: String,
    val phone: String,
    val preview: String,
    val time: String,
    val unread: Int
)
data class ChatMessage(
    val id: String,
    val text: String,
    val direction: String,
    val time: String,
    val status: String
)
class ApiException(val httpStatus: Int, message: String): Exception(message)

/** A sessão do backend NÃO é um token Meta e não sai do app para outros hosts. */
class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("session_private", Context.MODE_PRIVATE)
    private val alias = "ponte_mobile_session_aes"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("ciphertext", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun read(): String? {
        val encoded = prefs.getString("ciphertext", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }
    fun clear() { prefs.edit().remove("ciphertext").apply() }
}

class PonteApi(private val session: SecureSession) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun request(path: String, method: String = "GET", data: JSONObject? = null, authenticated: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            // path é construído somente por rotas internas, nunca por dados do servidor.
            val builder = Request.Builder().url(BuildConfig.API_BASE_URL + path).header("Accept", "application/json")
            if (authenticated) {
                val token = session.read() ?: throw ApiException(401, "Faça login novamente.")
                builder.header("Authorization", "Bearer $token")
            }
            if (method != "GET") {
                builder.method(method, (data ?: JSONObject()).toString().toRequestBody(jsonType))
            }
            client.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                if (!response.isSuccessful) {
                    val error = obj.optString("error").ifBlank { "Erro HTTP ${response.code}" }
                    throw ApiException(response.code, error)
                }
                if (raw.isNotEmpty() && obj.length() == 0) throw ApiException(502, "Resposta inválida do servidor.")
                obj
            }
        }

    suspend fun login(email: String, password: String): String {
        val result = request("auth/login", "POST", JSONObject().put("email", email).put("password", password), false)
        return result.optString("access_token").takeIf { it.length >= 20 }
            ?: throw ApiException(502, "O backend não retornou uma sessão válida.")
    }

    suspend fun conversations(): List<ChatSummary> {
        val items = request("conversations").optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            ChatSummary(row.optString("id"), row.optString("name").ifBlank { row.optString("phone") },
                row.optString("phone"), row.optString("last_message"), row.optString("updated_at"),
                row.optInt("unread_count"))
        }.filter { it.id.isNotBlank() }
    }

    suspend fun messages(conversationId: String): List<ChatMessage> {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        val items = request("conversations/$id/messages").optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            ChatMessage(row.optString("id"), row.optString("text"), row.optString("direction"),
                row.optString("created_at"), row.optString("status"))
        }
    }

    suspend fun createConversation(phone: String): String {
        val result = request("conversations", "POST", JSONObject().put("phone", phone))
        return result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502, "O backend não retornou a conversa.")
    }

    suspend fun send(conversationId: String, text: String) {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        request("conversations/$id/messages", "POST", JSONObject().put("text", text))
    }

    suspend fun registerDevice(fcmToken: String) {
        request("devices", "POST", JSONObject().put("platform", "android").put("fcm_token", fcmToken))
    }

    suspend fun logout() {
        try { request("auth/logout", "POST") } finally { session.clear() }
    }
}
