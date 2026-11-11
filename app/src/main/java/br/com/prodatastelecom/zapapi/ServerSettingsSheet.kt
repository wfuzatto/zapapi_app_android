package br.com.prodatastelecom.zapapi

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsSheet(
    initial: ServerConfig,
    api: PonteApi,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var working by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Configurações do servidor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("Acesso ao ZapApi V1 • HTTPS obrigatório • Sem credenciais da Meta no aplicativo.",
                style = MaterialTheme.typography.bodySmall)

            Text("Servidor", style = MaterialTheme.typography.titleMedium)
            SettingField("URL base da plataforma", draft.baseUrl) {
                draft = draft.copy(baseUrl = it); validationError = null
            }
            SettingField("Prefixo da API móvel", draft.apiPrefix) {
                draft = draft.copy(apiPrefix = it)
            }
            SettingField("Empresa / workspace (opcional)", draft.workspace) {
                draft = draft.copy(workspace = it)
            }

            HorizontalDivider()
            Text("Rotas da API (relativas à URL base + prefixo)", style = MaterialTheme.typography.titleMedium)
            SettingField("Login (POST)", draft.loginPath) { draft = draft.copy(loginPath = it) }
            SettingField("Listar conversas (GET)", draft.conversationsPath) { draft = draft.copy(conversationsPath = it) }
            SettingField("Mensagens da conversa (GET, {id})", draft.messagesPath) { draft = draft.copy(messagesPath = it) }
            SettingField("Enviar mensagem (POST, {id})", draft.sendPath) { draft = draft.copy(sendPath = it) }
            SettingField("Iniciar conversa (POST)", draft.createPath) { draft = draft.copy(createPath = it) }
            SettingField("Listar contatos (reservado)", draft.contactsPath) { draft = draft.copy(contactsPath = it) }
            SettingField("Upload de mídia (reservado)", draft.mediaPath) { draft = draft.copy(mediaPath = it) }
            SettingField("Firebase / registrar dispositivo (POST)", draft.pushPath) { draft = draft.copy(pushPath = it) }
            SettingField("Logout (POST)", draft.logoutPath) { draft = draft.copy(logoutPath = it) }

            HorizontalDivider()
            Text("Conexão e diagnóstico", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exigir HTTPS", modifier = Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null, enabled = false)
            }
            Text("HTTPS e bloqueio de redirecionamentos de login são obrigatórios para proteger a sessão.",
                style = MaterialTheme.typography.bodySmall)
            SettingField("Timeout de conexão (5 a 120 segundos)", draft.connectionTimeoutSeconds) {
                draft = draft.copy(connectionTimeoutSeconds = it.filter(Char::isDigit).take(3))
            }
            SettingField("Timeout de leitura (5 a 120 segundos)", draft.readTimeoutSeconds) {
                draft = draft.copy(readTimeoutSeconds = it.filter(Char::isDigit).take(3))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logs técnicos (sem tokens ou mensagens)", modifier = Modifier.weight(1f))
                Switch(checked = draft.debugMode, onCheckedChange = { draft = draft.copy(debugMode = it) })
            }

            if (validationError != null) {
                Text(validationError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            if (diagnostics != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Text(diagnostics.orEmpty(), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = {
                    diagnostics = null
                    validationError = draft.validate()
                    if (validationError == null) {
                        working = true
                        scope.launch {
                            try { diagnostics = api.testConnection(draft) }
                            catch (e: Exception) { diagnostics = e.message ?: "Erro desconhecido." }
                            finally { working = false }
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Testar conexão (sem enviar senha)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { draft = ServerConfig(); diagnostics = null; validationError = null },
                    modifier = Modifier.weight(1f)
                ) { Text("Restaurar padrão") }
                Button(
                    onClick = {
                        validationError = draft.validate()
                        if (validationError == null) onSave(draft.normalized())
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Salvar") }
            }
            Text("Se o teste mostrar HTTP 302, a rota ainda redireciona. É preciso instalar a API móvel JSON no backend V1; alterar o endereço não transforma uma página web em API.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingField(title: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(title) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
