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
import java.io.File
import java.io.FileOutputStream
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
    val unread: Int,
    val windowOpen: Boolean = true
)
data class ChatMessage(
    val id: String,
    val text: String,
    val direction: String,
    val time: String,
    val status: String,
    val type: String = "text",
    val hasMedia: Boolean = false,
    val mediaMime: String = "",
    val mediaFilename: String = "",
    val isPending: Boolean = false,
    val serverMessageId: String = ""
)
data class MessagePage(
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
    val windowOpen: Boolean? = null
)
data class MessageSync(val messages: List<ChatMessage>, val windowOpen: Boolean?)
data class MessageSendReceipt(val id: String, val status: String)
data class MessageTemplate(
    val name: String,
    val language: String,
    val category: String,
    val header: String,
    val body: String,
    val headerVariables: Int,
    val variables: Int
)
data class MediaAttachment(val file: File, val mimeType: String, val filename: String)
class ApiException(val httpStatus: Int, message: String): Exception(message)

/** A sessão do backend NÃO é um token Meta e não sai do app para outros hosts. */
class SecureSession(context: Context) {
    internal val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("session_private", Context.MODE_PRIVATE)
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
    // Exibe ao operador o servidor real antes de enviar credenciais.
    fun currentServer(): String = settings.load().normalized().baseUrl
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val maxMediaBytes = 32L * 1024L * 1024L
    private val maxMediaCacheBytes = 128L * 1024L * 1024L
    private val messageCache = LocalMessageCache(session.appContext)

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
        val health = probe("API móvel: saúde", cfg.endpoint("health"))
        val login = probe("API móvel: login (GET sem credenciais)", cfg.endpoint(cfg.loginPath))
        health + "\n\n" + login + "\n\nO teste não envia a senha. O ZapApi " +
            "precisa responder JSON em /" + cfg.apiPrefix + "/" + cfg.loginPath +
            ". HTTP 302 não é sucesso de autenticação."
    }

    suspend fun login(email: String, password: String, mfaCode: String = ""): String {
        val code = mfaCode.filter(Char::isDigit)
        if (code.isNotEmpty() && code.length != 6)
            throw ApiException(400, "O código autenticador deve conter seis dígitos.")
        val result = request(settings.load().loginPath, "POST",
            JSONObject().put("email", email).put("password", password)
                .put("mfa_code", code), false)
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
                row.optInt("unread_count"), row.optBoolean("window_open", true))
        }.filter { it.id.isNotBlank() }
    }

    private fun messageCacheKey(): String {
        val token = session.read() ?: throw ApiException(401, "Faça login novamente.")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun clearMessageCache() = messageCache.clearAll()

    suspend fun cachedLatestMessages(conversationId: String): MessagePage = withContext(Dispatchers.IO) {
        val key = messageCacheKey()
        val page = messageCache.latest(key, conversationId)
        val pending = messageCache.pending(key, conversationId)
        MessagePage((page.messages + pending).sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }, page.hasMore)
    }

    suspend fun cachePendingMessage(conversationId: String, message: ChatMessage) = withContext(Dispatchers.IO) {
        messageCache.savePending(messageCacheKey(), conversationId, message)
    }

    suspend fun attachPendingMessageId(conversationId: String, localId: String, serverId: String) =
        withContext(Dispatchers.IO) {
            messageCache.attachPendingId(messageCacheKey(), conversationId, localId, serverId)
        }

    suspend fun removePendingMessage(conversationId: String, localId: String) = withContext(Dispatchers.IO) {
        messageCache.removePending(messageCacheKey(), conversationId, localId)
    }

    private fun messagesPath(conversationId: String): String {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        return settings.load().messagesPath.replace("{id}", id)
    }

    private fun firstNonBlank(row: JSONObject, vararg keys: String): String =
        keys.asSequence().map { row.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun knownMediaType(value: String): String? = when (value.trim().lowercase()) {
        "image", "photo", "picture", "imagem", "foto" -> "image"
        "audio", "voice", "ptt", "voice_note", "áudio", "voz" -> "audio"
        "video", "vídeo", "clip", "gif", "animated_gif" -> "video"
        "sticker", "sticker_image", "figurinha", "figurinha_animada" -> "sticker"
        "document", "file", "attachment", "documento", "arquivo" -> "document"
        else -> null
    }

    private fun placeholderMediaType(text: String): String? {
        val match = Regex("^\\s*\\[(image|photo|video|gif|audio|sticker|document|file)\\]", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return knownMediaType(match.groupValues[1])
    }

    private fun parseMessages(items: JSONArray): List<ChatMessage> =
        (0 until items.length()).mapNotNull { i ->
            val row = items.optJSONObject(i) ?: return@mapNotNull null
            val media = row.optJSONObject("media")
            val mime = firstNonBlank(row, "media_mime", "mime_type", "mimetype", "mime")
                .ifBlank { media?.let { firstNonBlank(it, "media_mime", "mime_type", "mimetype", "mime") }.orEmpty() }
            val text = firstNonBlank(row, "text", "caption")
            val rawType = firstNonBlank(row, "type", "message_type", "media_type")
            val type = knownMediaType(rawType) ?: placeholderMediaType(text) ?: rawType.ifBlank { "text" }
            val mediaId = firstNonBlank(row, "media_id").ifBlank { media?.optString("id").orEmpty() }
            val explicitHasMedia = row.optBoolean("has_media", false) ||
                row.optString("has_media").equals("1", ignoreCase = true) ||
                row.optString("has_media").equals("true", ignoreCase = true)
            val hasMedia = explicitHasMedia || mime.isNotBlank() || mediaId.isNotBlank() ||
                type in setOf("image", "audio", "video", "sticker", "document")
            val filename = firstNonBlank(row, "media_filename", "filename", "file_name")
                .ifBlank { media?.let { firstNonBlank(it, "filename", "file_name", "name") }.orEmpty() }
            row.optString("id").takeIf { it.isNotBlank() }?.let { id ->
                ChatMessage(id, text, row.optString("direction"), row.optString("created_at"),
                    row.optString("status"), type, hasMedia, mime, filename)
            }
        }

    suspend fun latestMessages(conversationId: String): MessagePage = withContext(Dispatchers.IO) {
        val key = messageCacheKey()
        val result = request(messagesPath(conversationId) + "?limit=${LocalMessageCache.PAGE_SIZE}")
        val raw = parseMessages(result.optJSONArray("data") ?: JSONArray())
        val pageMessages = raw.takeLast(LocalMessageCache.PAGE_SIZE)
        messageCache.save(key, conversationId, pageMessages)
        val serverHasMore = result.optBoolean("has_more", raw.size > LocalMessageCache.PAGE_SIZE)
        messageCache.setHasMore(key, conversationId, serverHasMore)
        val page = messageCache.latest(key, conversationId)
        val pending = messageCache.pending(key, conversationId)
        MessagePage((page.messages + pending).sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE },
            serverHasMore || page.hasMore, result.optBoolean("window_open", true))
    }

    suspend fun olderMessages(conversationId: String, beforeId: String): MessagePage =
        withContext(Dispatchers.IO) {
            val key = messageCacheKey()
            val cached = messageCache.before(key, conversationId, beforeId)
            if (cached.messages.isNotEmpty()) return@withContext MessagePage(cached.messages, cached.hasMore)
            try {
                val path = messagesPath(conversationId) + "?limit=${LocalMessageCache.PAGE_SIZE}&before_id=" +
                    URLEncoder.encode(beforeId, "UTF-8")
                val result = request(path)
                val raw = parseMessages(result.optJSONArray("data") ?: JSONArray())
                val before = beforeId.toLongOrNull() ?: Long.MAX_VALUE
                val older = raw.filter { (it.id.toLongOrNull() ?: Long.MAX_VALUE) < before }
                    .sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
                if (older.isEmpty()) {
                    messageCache.setHasMore(key, conversationId, false)
                    return@withContext MessagePage(emptyList(), false)
                }
                val pageMessages = older.takeLast(LocalMessageCache.PAGE_SIZE)
                messageCache.save(key, conversationId, pageMessages)
                val serverHasMore = result.optBoolean("has_more", older.size > LocalMessageCache.PAGE_SIZE)
                messageCache.setHasMore(key, conversationId, serverHasMore)
                MessagePage(pageMessages, serverHasMore, result.optBoolean("window_open", true))
            } catch (e: Exception) {
                if (cached.messages.isNotEmpty()) MessagePage(cached.messages, cached.hasMore)
                else throw e
            }
        }

    /** Fetches only new rows plus statuses for the currently loaded window and pending sends. */
    suspend fun syncMessages(
        conversationId: String,
        afterId: String,
        statusIds: List<String>,
        pendingIds: Set<String>
    ): MessageSync = withContext(Dispatchers.IO) {
        val key = messageCacheKey()
        val after = afterId.toLongOrNull() ?: 0L
        val storedPending = messageCache.pending(key, conversationId)
        val allPendingIds = pendingIds + storedPending.map { it.serverMessageId }.filter { it.isNotBlank() }
        val ids = allPendingIds.asSequence().mapNotNull { it.toLongOrNull() }
            .plus(statusIds.asSequence().mapNotNull { it.toLongOrNull() })
            .distinct().take(50).toList()
        val query = buildString {
            append("?after_id=").append(after)
            if (ids.isNotEmpty()) append("&ids=").append(ids.joinToString(","))
        }
        val result = request(messagesPath(conversationId) + query)
        val all = parseMessages(result.optJSONArray("data") ?: JSONArray())
        val include = ids.map { it.toString() }.toSet()
        val changed = all.filter {
            val id = it.id.toLongOrNull() ?: return@filter false
            id > after || it.id in include
        }
        changed.forEach { message ->
            val localPending = storedPending.firstOrNull { it.serverMessageId == message.id }
            if (localPending != null && !message.status.equals("accepted", ignoreCase = true))
                messageCache.removePending(key, conversationId, localPending.id)
        }
        messageCache.save(key, conversationId, changed.filterNot {
            it.id in allPendingIds && it.status.equals("accepted", ignoreCase = true)
        })
        MessageSync(changed, result.optBoolean("window_open", true))
    }

    /** Baixa mídia de uma mensagem pelo endpoint móvel autenticado e guarda no cache privado do app. */
    suspend fun downloadMedia(messageId: String, filename: String, expectedMime: String): MediaAttachment =
        withContext(Dispatchers.IO) {
            val id = messageId.takeIf { it.length in 1..18 && it.all(Char::isDigit) && it.any { c -> c != '0' } }
                ?: throw ApiException(400, "Identificador de mídia inválido.")
            val cfg = settings.load().normalized()
            cfg.validate()?.let { throw ApiException(400, "Configurações: $it") }
            val template = cfg.mediaPath.trim('/')
            val encodedId = URLEncoder.encode(id, "UTF-8")
            val path = if (template.contains("{id}")) template.replace("{id}", encodedId)
                else template.trimEnd('/') + "/" + encodedId
            val cacheDir = File(session.appContext.cacheDir, "whatsapp_media")
            if (!cacheDir.exists() && !cacheDir.mkdirs())
                throw ApiException(500, "Não foi possível preparar o cache privado de mídia.")
            val cached = File(cacheDir, "message_$id.media")
            if (cached.isFile && cached.length() > 0L) {
                cached.setLastModified(System.currentTimeMillis())
                trimMediaCache(cacheDir, cached)
                return@withContext MediaAttachment(cached, expectedMime, filename)
            }

            val token = session.read() ?: throw ApiException(401, "Faça login novamente.")
            val request = Request.Builder().url(cfg.endpoint(path))
                .header("Accept", "image/*,audio/*,video/*,application/pdf,application/octet-stream")
                .header("Authorization", "Bearer $token").get().build()
            client(cfg).newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    throw ApiException(response.code, "A rota da mídia redirecionou para ${safeLocation(response.header("Location"))}.")
                }
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty().take(1000)
                    val detail = try { JSONObject(raw).optString("error").ifBlank { "Erro HTTP ${response.code}" } }
                        catch (_: Exception) { "Erro HTTP ${response.code}" }
                    throw ApiException(response.code, detail.take(260))
                }
                val body = response.body ?: throw ApiException(502, "A resposta da mídia veio vazia.")
                if (body.contentLength() > maxMediaBytes)
                    throw ApiException(413, "O arquivo excede o limite de 32 MB.")
                val headerMime = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                val fallbackMime = expectedMime.takeIf { Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$").matches(it) }
                    ?: "application/octet-stream"
                val mime = headerMime.takeIf { Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$").matches(it) }
                    ?: fallbackMime
                val temp = File.createTempFile("media_${id}_", ".part", cacheDir)
                try {
                    var total = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(temp).buffered().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxMediaBytes) throw ApiException(413, "O arquivo excede o limite de 32 MB.")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (total == 0L) throw ApiException(502, "O arquivo de mídia veio vazio.")
                    cached.delete()
                    if (!temp.renameTo(cached)) throw ApiException(500, "Não foi possível salvar a mídia no cache privado.")
                    trimMediaCache(cacheDir, cached)
                    MediaAttachment(cached, mime, filename)
                } catch (e: Exception) {
                    temp.delete()
                    throw e
                }
            }
        }

    private fun trimMediaCache(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".media") }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + (directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".thumb.jpg") }?.sumOf { it.length() } ?: 0L)
        for (file in files) {
            if (total <= maxMediaCacheBytes) break
            if (file.absolutePath == keep.absolutePath) continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                val thumbnail = File(directory, file.name.removeSuffix(".media") + ".thumb.jpg")
                val thumbnailSize = thumbnail.length()
                if (thumbnail.delete()) total -= thumbnailSize
            }
        }
    }

    suspend fun createConversation(phone: String): String {
        val result = request(settings.load().createPath, "POST", JSONObject().put("phone", phone))
        return result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502, "A API não retornou o ID da conversa.")
    }

    suspend fun send(conversationId: String, text: String): MessageSendReceipt {
        val id = URLEncoder.encode(conversationId, "UTF-8")
        val result = request(settings.load().sendPath.replace("{id}", id), "POST", JSONObject().put("text", text))
        return MessageSendReceipt(result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502, "A API não retornou o ID da mensagem."), result.optString("status"))
    }

    suspend fun templates(conversationId: String): List<MessageTemplate> = withContext(Dispatchers.IO) {
        val cfg = settings.load()
        val accountId = conversationId.substringBefore('-').toLongOrNull()
            ?: throw ApiException(400, "Conversa inválida para carregar templates.")
        val path = cfg.templatesPath + "?account_id=" + accountId
        val rows = request(path).optJSONArray("data") ?: JSONArray()
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val languageValue = row.opt("language")
            val language = when (languageValue) {
                is JSONObject -> languageValue.optString("code")
                else -> languageValue?.toString().orEmpty()
            }
            val name = row.optString("name").trim()
            if (name.isBlank() || language.isBlank()) return@mapNotNull null
            MessageTemplate(name, language, row.optString("category"), row.optString("header"),
                row.optString("body"), row.optInt("header_variables"), row.optInt("variables"))
        }
    }

    suspend fun sendTemplate(
        conversationId: String,
        template: MessageTemplate,
        bodyParameters: List<String>,
        headerParameters: List<String>
    ): MessageSendReceipt {
        val encodedId = URLEncoder.encode(conversationId, "UTF-8")
        val path = settings.load().templateSendPath.replace("{id}", encodedId)
        val body = JSONObject().put("name", template.name).put("language", template.language)
            .put("parameters", JSONArray(bodyParameters)).put("header_parameters", JSONArray(headerParameters))
        val result = request(path, "POST", body)
        return MessageSendReceipt(result.optString("id").takeIf { it.isNotBlank() }
            ?: throw ApiException(502, "A API não retornou o ID da mensagem."), result.optString("status"))
    }

    suspend fun registerDevice(fcmToken: String) {
        request(settings.load().pushPath, "POST",
            JSONObject().put("platform", "android").put("fcm_token", fcmToken))
    }

    suspend fun logout() {
        try { request(settings.load().logoutPath, "POST") } finally { session.clear() }
    }
}
