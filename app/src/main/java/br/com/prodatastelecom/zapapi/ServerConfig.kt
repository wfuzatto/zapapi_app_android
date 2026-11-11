package br.com.prodatastelecom.zapapi

import android.content.Context
import java.net.URI

data class ServerConfig(
    val baseUrl: String = "https://prodatastelecom.com.br/sites/whatsapp_gateway",
    val apiPrefix: String = "mobile-api/v1",
    val loginPath: String = "auth/login",
    val conversationsPath: String = "conversations",
    val messagesPath: String = "conversations/{id}/messages",
    val sendPath: String = "conversations/{id}/messages",
    val createPath: String = "conversations",
    val contactsPath: String = "contacts",
    val mediaPath: String = "media",
    val pushPath: String = "devices",
    val logoutPath: String = "auth/logout",
    val workspace: String = "",
    val connectionTimeoutSeconds: String = "20",
    val readTimeoutSeconds: String = "30",
    val forceHttps: Boolean = true,
    val debugMode: Boolean = false
) {
    /** Retorna mensagem de validação; rotas relativas evitam envio de tokens a outros domínios. */
    fun validate(): String? {
        val uri = try { URI(baseUrl.trim()) } catch (_: Exception) { return "URL base inválida." }
        if (uri.scheme?.lowercase() != "https") return "A URL base deve começar com https://."
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
            return "Informe uma URL HTTPS sem usuário, parâmetros ou fragmento."
        val paths = listOf(apiPrefix, loginPath, conversationsPath, messagesPath, sendPath,
            createPath, contactsPath, mediaPath, pushPath, logoutPath)
        if (paths.any { it.isBlank() || it.startsWith("/") || it.contains("..") ||
                    it.contains("?") || it.contains("#") || it.contains("\\") ||
                    !Regex("^[a-zA-Z0-9_./{}-]+$").matches(it) })
            return "Os endpoints devem ser caminhos relativos, sem domínio ou parâmetros."
        if (messagesPath.contains("{id}").not() || sendPath.contains("{id}").not())
            return "As rotas de mensagens devem conter {id}."
        if (connectionTimeoutSeconds.toIntOrNull() !in 5..120 || readTimeoutSeconds.toIntOrNull() !in 5..120)
            return "Os timeouts devem estar entre 5 e 120 segundos."
        if (workspace.length > 64 || (workspace.isNotBlank() && !Regex("^[a-zA-Z0-9_-]+$").matches(workspace)))
            return "A empresa deve ser um identificador simples (sem espaços)."
        return null
    }

    fun normalized(): ServerConfig = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiPrefix = apiPrefix.trim().trim('/'),
        loginPath = loginPath.trim().trim('/'),
        conversationsPath = conversationsPath.trim().trim('/'),
        messagesPath = messagesPath.trim().trim('/'),
        sendPath = sendPath.trim().trim('/'),
        createPath = createPath.trim().trim('/'),
        contactsPath = contactsPath.trim().trim('/'),
        mediaPath = mediaPath.trim().trim('/'),
        pushPath = pushPath.trim().trim('/'),
        logoutPath = logoutPath.trim().trim('/'),
        workspace = workspace.trim()
    )

    fun endpoint(path: String): String = "${baseUrl.trimEnd('/')}/${apiPrefix.trim('/')}/${path.trimStart('/')}"
}

class ServerConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("ponte_mobile_server", Context.MODE_PRIVATE)

    fun load(): ServerConfig {
        val defaults = ServerConfig()
        return ServerConfig(
            baseUrl = (prefs.getString("baseUrl", defaults.baseUrl) ?: defaults.baseUrl)
                .let { if(it=="https://prodatastelecom.com.br/sites/whatsapp_gateway")
                    "https://zapapi.inf.br" else it },
            apiPrefix = prefs.getString("apiPrefix", defaults.apiPrefix) ?: defaults.apiPrefix,
            loginPath = prefs.getString("loginPath", defaults.loginPath) ?: defaults.loginPath,
            conversationsPath = prefs.getString("conversationsPath", defaults.conversationsPath) ?: defaults.conversationsPath,
            messagesPath = prefs.getString("messagesPath", defaults.messagesPath) ?: defaults.messagesPath,
            sendPath = prefs.getString("sendPath", defaults.sendPath) ?: defaults.sendPath,
            createPath = prefs.getString("createPath", defaults.createPath) ?: defaults.createPath,
            contactsPath = prefs.getString("contactsPath", defaults.contactsPath) ?: defaults.contactsPath,
            mediaPath = prefs.getString("mediaPath", defaults.mediaPath) ?: defaults.mediaPath,
            pushPath = prefs.getString("pushPath", defaults.pushPath) ?: defaults.pushPath,
            logoutPath = prefs.getString("logoutPath", defaults.logoutPath) ?: defaults.logoutPath,
            workspace = prefs.getString("workspace", defaults.workspace) ?: defaults.workspace,
            connectionTimeoutSeconds = prefs.getString("connectionTimeoutSeconds", defaults.connectionTimeoutSeconds) ?: defaults.connectionTimeoutSeconds,
            readTimeoutSeconds = prefs.getString("readTimeoutSeconds", defaults.readTimeoutSeconds) ?: defaults.readTimeoutSeconds,
            forceHttps = prefs.getBoolean("forceHttps", true),
            debugMode = prefs.getBoolean("debugMode", false)
        )
    }

    fun save(config: ServerConfig) {
        val safe = config.normalized()
        require(safe.validate() == null) { safe.validate() ?: "Configuração inválida." }
        prefs.edit()
            .putString("baseUrl", safe.baseUrl)
            .putString("apiPrefix", safe.apiPrefix)
            .putString("loginPath", safe.loginPath)
            .putString("conversationsPath", safe.conversationsPath)
            .putString("messagesPath", safe.messagesPath)
            .putString("sendPath", safe.sendPath)
            .putString("createPath", safe.createPath)
            .putString("contactsPath", safe.contactsPath)
            .putString("mediaPath", safe.mediaPath)
            .putString("pushPath", safe.pushPath)
            .putString("logoutPath", safe.logoutPath)
            .putString("workspace", safe.workspace)
            .putString("connectionTimeoutSeconds", safe.connectionTimeoutSeconds)
            .putString("readTimeoutSeconds", safe.readTimeoutSeconds)
            .putBoolean("forceHttps", safe.forceHttps)
            .putBoolean("debugMode", safe.debugMode)
            .apply()
    }

    fun reset() { prefs.edit().clear().apply() }
}
