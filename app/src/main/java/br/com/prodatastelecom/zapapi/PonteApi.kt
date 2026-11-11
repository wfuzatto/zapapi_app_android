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

data class ZapAccount(val id:Int,val name:String,val phoneNumberId:String)
data class ZapContact(val phone:String,val name:String,val accountId:Int)
data class ZapTemplate(val name:String,val language:String,val category:String,val header:String,val body:String,val headerVariables:Int,val variables:Int,val accountId:Int)

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

class PonteApi(private val session: SecureSession, private val settings: ServerConfigStore) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun client(config: ServerConfig): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectionTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        // Nunca seguir redirects autenticados para outro host.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun safeLocation(location: String?): String {
        if (location.isNullOrBlank()) return "(header Location ausente)"
        return location.substringBefore('?').substringBefore('#').take(240)
    }

    private suspend fun request(path: String, method: String = "GET",
                                data: JSONObject? = null, authenticated: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val cfg = settings.load().normalized()
            cfg.validate()?.let { throw ApiException(400, "Configurações: " + it) }
            val builder = Request.Builder().url(cfg.endpoint(path)).header("Accept", "application/json")
            if (authenticated) {
                val token = session.read() ?: throw ApiException(401, "Faça login novamente.")
                builder.header("Authorization", "Bearer " + token)
            }
            if (cfg.workspace.isNotBlank()) builder.header("X-Workspace-Slug", cfg.workspace)
            if (method != "GET") {
                builder.method(method, (data ?: JSONObject()).toString().toRequestBody(jsonType))
            }
            client(cfg).newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (cfg.debugMode) {
                    // Sem cabeçalhos secretos, corpo ou senha nos logs.
                    android.util.Log.d("ZapApiAPI", method + " " + cfg.apiPrefix + "/" + path +
                        " -> HTTP " + response.code)
                }
                if (response.code in 300..399) {
                    throw ApiException(response.code,
                        "HTTP " + response.code + ": redirecionado para " +
                        safeLocation(response.header("Location")) +
                        ". A API móvel pode não existir ou estar redirecionando para o painel web.")
                }
                val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                if (!response.isSuccessful) {
                    val error = obj.optString("error").ifBlank {
                        obj.optString("message").ifBlank { "Erro HTTP " + response.code }
                    }
                    throw ApiException(response.code, error.take(260))
                }
                if (obj.length() == 0) throw ApiException(502,
                    "HTTP " + response.code + " sem JSON válido. Verifique se esta rota é a API móvel.")
                obj
            }
        }

    /** Apenas GET público e sem credenciais, sem alterações no servidor. */
    suspend fun testConnection(configInput: ServerConfig): String = withContext(Dispatchers.IO) {
        val cfg = configInput.normalized()
        cfg.validate()?.let { return@withContext "Configuração inválida: " + it }
        val http = client(cfg)
        fun probe(title: String, url: String): String {
            return try {
                http.newCall(Request.Builder().url(url).get().header("Accept", "application/json").build())
                    .execute().use { response ->
                        val kind = response.header("Content-Type").orEmpty().take(60)
                        val destination = if (response.code in 300..399)
                            "\nDestino: " + safeLocation(response.header("Location")) else ""
                        val detail = when {
                            response.code in 300..399 -> "\nRedirecionamento: verificar a rota e a autenticação."
                            response.code == 404 -> "\nRota não encontrada."
                            response.code == 405 -> "\nGET não permitido: pode ser uma rota POST."
                            response.code == 401 || response.code == 403 -> "\nEndpoint protegido."
                            else -> ""
                        }
                        title + ": HTTP " + response.code + " (" + kind + ")" + destination + detail
                    }
            } catch (e: Exception) {
                title + ": falha (" + e.javaClass.simpleName + ": " +
                    (e.message?.take(120) ?: "sem detalhes") + ")"
            }
        }
        val site = probe("Site HTTPS", cfg.baseUrl + "/")
        val health = probe("API móvel: saúde", cfg.endpoint("health"))
        val login = probe("API móvel: login (GET sem credenciais)", cfg.endpoint(cfg.loginPath))
        site + "\n\n" + health + "\n\n" + login + "\n\nO teste não envia a senha. O ZapApi " +
            "precisa responder JSON em /" + cfg.apiPrefix + "/" + cfg.loginPath +
            ". HTTP 302 não é sucesso de autenticação."
    }

    suspend fun login(email: String, password: String): String {
        val result = request(settings.load().loginPath, "POST",
            JSONObject().put("email", email).put("password", password), false)
        return result.optString("access_token").takeIf { it.length >= 20 }
            ?: throw ApiException(502,
                "A API não retornou access_token válido. O login do painel web não é a API móvel.")
    }

    suspend fun conversations(): List<ChatSummary> {
        val items = request(settings.load().conversationsPath).optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            ChatSummary(row.optString("id"), row.optString("name").ifBlank { row.optString("phone") },
                row.optString("phone"), row.optString("last_message"), row.optString("updated_at"),
                row.optInt("unread_count"))
        }.filter { it.id.isNotBlank() }
    }

    suspend fun messages(conversationId: String): List<ChatMessage> {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        val path = settings.load().messagesPath.replace("{id}", id)
        val items = request(path).optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            ChatMessage(row.optString("id"), row.optString("text"), row.optString("direction"),
                row.optString("created_at"), row.optString("status"))
        }
    }

    suspend fun createConversation(phone: String): String {
        val result = request(settings.load().createPath, "POST", JSONObject().put("phone", phone))
        return result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502, "A API não retornou o ID da conversa.")
    }

    suspend fun send(conversationId: String, text: String) {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        request(settings.load().sendPath.replace("{id}", id), "POST", JSONObject().put("text", text))
    }

    suspend fun registerDevice(fcmToken: String) {
        request(settings.load().pushPath, "POST",
            JSONObject().put("platform", "android").put("fcm_token", fcmToken))
    }

    suspend fun accounts(): List<ZapAccount> {
        val items=request("accounts").optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item=items.optJSONObject(index) ?: return@mapNotNull null
            val id=item.optInt("id")
            if(id<=0)return@mapNotNull null
            ZapAccount(id,item.optString("name"),item.optString("phone_number_id"))
        }
    }
    suspend fun contacts(): List<ZapContact> {
        val items=request(settings.load().contactsPath).optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item=items.optJSONObject(index) ?: return@mapNotNull null
            val phone=item.optString("phone")
            if(phone.isBlank())return@mapNotNull null
            ZapContact(phone,item.optString("name").ifBlank { phone },item.optInt("account_id"))
        }
    }
    suspend fun templates(accountId: Int): List<ZapTemplate> {
        val data=request("templates?account_id="+accountId)
        val arr=data.optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { index ->
            val item=arr.optJSONObject(index) ?: return@mapNotNull null
            val name=item.optString("name")
            if(name.isBlank())return@mapNotNull null
            ZapTemplate(name,item.optString("language"),item.optString("category"),
                item.optString("header"),item.optString("body"),
                item.optInt("header_variables"),item.optInt("variables"),item.optInt("account_id"))
        }
    }
    suspend fun sendTemplate(conversationId:String,template:ZapTemplate,values:List<String>,headerValues:List<String>) {
        val id=URLEncoder.encode(conversationId,"UTF-8")
        val payload=JSONObject().put("name",template.name).put("language",template.language)
            .put("parameters",JSONArray(values)).put("header_parameters",JSONArray(headerValues))
        request("conversations/$id/templates","POST",payload)
    }
    suspend fun createConversation(phone:String,accountId:Int):String {
        val result=request(settings.load().createPath,"POST",
            JSONObject().put("phone",phone).put("account_id",accountId))
        return result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502,"A API não retornou o ID da conversa.")
    }

    suspend fun logout() {
        try { request(settings.load().logoutPath, "POST") } finally { session.clear() }
    }
}
