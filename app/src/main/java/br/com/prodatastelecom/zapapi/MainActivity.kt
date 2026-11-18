package br.com.prodatastelecom.zapapi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

private val Green = Color(0xFF087F68)
private val LightGreen = Color(0xFFE7F6EE)
private val ChatBackground = Color(0xFFF5F6F2)
private val Ink = Color(0xFF132722)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws edge-to-edge for targetSdk 35. Keep content inside
        // the system bars and let Compose handle the IME insets per screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val session = SecureSession(this)
        val configs = ServerConfigStore(this)
        val migratedToOfficialServer = configs.migrateToOfficialServer()
        if (migratedToOfficialServer) session.clear()
        val api = PonteApi(session, configs)
        if (migratedToOfficialServer) api.clearMessageCache()
        val activityLifecycle = lifecycle
        setContent {
            val scope = rememberCoroutineScope()
            var isLoggedIn by remember { mutableStateOf(session.read() != null) }
            var activeChat by remember { mutableStateOf<ChatSummary?>(null) }
            var refreshKey by remember { mutableIntStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (isLoggedIn && FirebaseApp.getApps(this@MainActivity).isNotEmpty()) {
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        scope.launch { try { api.registerDevice(token) } catch (_: Exception) {} }
                    }
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Green, onPrimary = Color.White, background = Color.White, surface = Color.White
            )) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Color.White) {
                    if (!isLoggedIn) {
                        LoginScreen(api, session, onLogin = { isLoggedIn = true },
                            onSettings = { showSettings = true })
                    } else if (activeChat != null) {
                        val chat = activeChat!!
                        ChatScreen(chat, api, activityLifecycle, onBack = { activeChat = null }, onUnauthorized = {
                            api.clearMessageCache()
                            session.clear()
                            isLoggedIn = false
                            activeChat = null
                        })
                    } else {
                        HomeScreen(api, refreshKey, activityLifecycle, onChat = { activeChat = it },
                            onRefresh = { refreshKey++ },
                            onSettings = { showSettings = true },
                            onLogout = {
                                scope.launch {
                                    try { api.logout() } catch (_: Exception) { session.clear() }
                                    api.clearMessageCache()
                                    isLoggedIn = false
                                }
                            }, onUnauthorized = {
                                api.clearMessageCache()
                                session.clear()
                                isLoggedIn = false
                            })
                    }
                }
                if (showSettings) {
                    ServerSettingsSheet(
                        initial = configs.load(),
                        api = api,
                        onDismiss = { showSettings = false },
                        onSave = { newConfig ->
                            configs.save(newConfig)
                            api.clearMessageCache()
                            // Invalidar sessão anterior: nunca reutilizar token de outro servidor/tenant.
                            session.clear()
                            isLoggedIn = false
                            activeChat = null
                            refreshKey++
                            showSettings = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(api: PonteApi, session: SecureSession, onLogin: () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mfaCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0xFFF7FAF8))) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)).background(Green), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Forum, contentDescription = "ZapApi", tint = Color.White, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("ZapApi Messenger", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Suas conversas comerciais em qualquer lugar", color = Color.Gray)
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Senha") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = mfaCode,
            onValueChange = { mfaCode = it.filter(Char::isDigit).take(6) },
            label = { Text("Código autenticador (se ativado)") },
            supportingText = { Text("Se usa autenticação em duas etapas, informe os 6 dígitos do aplicativo autenticador.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(22.dp))
        Button(onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val token = api.login(email.trim(), password, mfaCode)
                    api.clearMessageCache()
                    session.save(token)
                    onLogin()
                } catch (e: Exception) { error = e.message ?: "Não foi possível conectar." }
                finally { busy = false }
            }
        }, enabled = !busy && email.isNotBlank() && password.isNotBlank() && (mfaCode.isEmpty() || mfaCode.length == 6),
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
            else Text("Entrar")
        }
        Spacer(Modifier.height(15.dp))
        Text("Entre com sua conta ZapApi. O token da Meta permanece protegido no servidor.",
            fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        Text("Servidor: " + api.currentServer(), fontSize = 11.sp, color = Color.Gray)
    }
    IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
        Icon(Icons.Default.Settings, contentDescription = "Configurações do servidor", tint = Green)
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(api: PonteApi, refreshKey: Int, lifecycle: Lifecycle, onChat: (ChatSummary) -> Unit,
                       onRefresh: () -> Unit, onSettings: () -> Unit,
                       onLogout: () -> Unit, onUnauthorized: () -> Unit) {
    val scope = rememberCoroutineScope()
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var unreadOnly by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var newPhone by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshKey, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    chats = api.conversations()
                    error = null
                } catch (e: ApiException) {
                    if (e.httpStatus == 401) { onUnauthorized(); return@repeatOnLifecycle }
                    error = e.message
                } catch (e: Exception) { error = e.message }
                loading = false
                delay(1000)
            }
        }
    }
    Scaffold(topBar = {
        Column {
            Row(Modifier.fillMaxWidth().background(Color(0xFF053D30)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("ZapApi", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 27.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Atualizar", tint=Color.White) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Configurações do servidor", tint=Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções", tint=Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Sair da conta") }, onClick = { menu = false; onLogout() })
                    }
                }
            }
            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Pesquisar conversas") },
                shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 14.dp).padding(top = 8.dp))
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !unreadOnly, onClick = { unreadOnly = false }, label = { Text("Todas") })
                FilterChip(selected = unreadOnly, onClick = { unreadOnly = true }, label = { Text("Não lidas") })
            }
            HorizontalDivider(color = Color(0xFFF0F1EF))
        }
    }, floatingActionButton = {
        if(tab==0) FloatingActionButton(onClick={newChat=true},
            containerColor=Green,contentColor=Color.White) {
            Icon(Icons.Default.AddComment,"Iniciar conversa")
        }
    }, bottomBar = {
        NavigationBar(containerColor=Color(0xFFF6FAF7)) {
            val titles=listOf("Conversas","Contatos","Templates","Mais")
            val icons=listOf(Icons.AutoMirrored.Filled.Chat,Icons.Default.People,
                Icons.Default.Description,Icons.Default.Apps)
            titles.forEachIndexed { index,title ->
                NavigationBarItem(selected=tab==index,onClick={tab=index},
                    icon={Icon(icons[index],contentDescription=title)},
                    label={Text(title,fontSize=10.sp)},alwaysShowLabel=true)
            }
        }
    }) { padding ->
        if(tab!=0) {
            Box(Modifier.padding(padding).fillMaxSize()){
                ZapExtras(tab,api,onChat,onSettings)
            }
        } else Column(Modifier.padding(padding).fillMaxSize()) {
            if (error != null) {
                Text("Não foi possível atualizar: $error", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp))
                TextButton(onClick = onRefresh) { Text("Tentar novamente") }
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                val shown = chats.filter {
                    (!unreadOnly || it.unread > 0) &&
                        (it.name.contains(search, ignoreCase = true) || it.phone.contains(search))
                }
                if (shown.isEmpty() && error == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Forum, null, tint = Green, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Nenhuma conversa encontrada", fontWeight = FontWeight.Medium)
                            Text("As mensagens recebidas pela Meta aparecerão aqui.", color = Color.Gray, fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn {
                        items(shown, key = { it.id }) { chat ->
                            Row(Modifier.fillMaxWidth().clickable { onChat(chat) }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(chat.name)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(chat.name, fontWeight = FontWeight.SemiBold, color = Ink,
                                            maxLines = 1, modifier = Modifier.weight(1f))
                                        Text(chat.time.takeLast(5), color = Color.Gray, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(chat.preview, color = Color.Gray, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f))
                                        if (chat.unread > 0) {
                                            Spacer(Modifier.width(7.dp))
                                            Badge(containerColor = Green) { Text(chat.unread.toString()) }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFEDEDEC))
                        }
                    }
                }
            }
        }
    }
    if (newChat) {
        AlertDialog(onDismissRequest = { newChat = false },
            title = { Text("Iniciar conversa") },
            text = {
                Column {
                    Text("Informe o número com código do país, sem + ou espaços.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = newPhone, onValueChange = { newPhone = it.filter(Char::isDigit) },
                        label = { Text("Telefone (ex.: 5535999999999)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Text("Fora da janela de 24 horas, o backend exigirá um template aprovado.",
                        fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(enabled = !creating && newPhone.length in 10..15, onClick = {
                    creating = true
                    scope.launch {
                        try {
                            val id = api.createConversation(newPhone)
                            newChat = false
                            onChat(ChatSummary(id, newPhone, newPhone, "", "", 0))
                        } catch (e: Exception) { error = e.message }
                        finally { creating = false }
                    }
                }) { Text("Abrir conversa") }
            },
            dismissButton = { TextButton(onClick = { newChat = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(49.dp).clip(CircleShape).background(Color(0xFFE4F1EA)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Green)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(chat: ChatSummary, api: PonteApi, lifecycle: Lifecycle,
                       onBack: () -> Unit, onUnauthorized: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var messages by remember(chat.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var pendingServerIds by remember(chat.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var input by remember(chat.id) { mutableStateOf("") }
    var error by remember(chat.id) { mutableStateOf<String?>(null) }
    var sending by remember(chat.id) { mutableStateOf(false) }
    var loadingOlder by remember(chat.id) { mutableStateOf(false) }
    var hasMoreOlder by remember(chat.id) { mutableStateOf(false) }
    var initialScrollDone by remember(chat.id) { mutableStateOf(false) }
    var showTemplate by remember(chat.id) { mutableStateOf(false) }
    var showInfo by remember(chat.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun mergeChangedMessages(changed: List<ChatMessage>, atBottom: Boolean) {
        if (changed.isEmpty()) return
        val pendingByServerId = pendingServerIds.entries.associate { it.value to it.key }
        val merged = messages.toMutableList()
        var addedNew = false
        changed.forEach { remote ->
            val pendingLocalId = pendingByServerId[remote.id]
            if (pendingLocalId != null) {
                if (remote.status.equals("accepted", ignoreCase = true)) return@forEach
                merged.removeAll { it.id == pendingLocalId }
                pendingServerIds = pendingServerIds - pendingLocalId
            }
            val existingIndex = merged.indexOfFirst { it.id == remote.id }
            if (existingIndex >= 0) merged[existingIndex] = remote
            else { merged += remote; addedNew = true }
        }
        messages = merged.distinctBy { it.id }
            .sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
        if (atBottom && addedNew) scope.launch {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(chat.id, lifecycle) {
        try {
            val cached = api.cachedLatestMessages(chat.id)
            messages = cached.messages
            pendingServerIds = cached.messages.filter { it.isPending && it.serverMessageId.isNotBlank() }
                .associate { it.id to it.serverMessageId }
            hasMoreOlder = cached.hasMore
            if (cached.messages.any { !it.isPending }) {
                // Put the existing SQLite page on screen at once, before network synchronization.
                yield()
                listState.scrollToItem(cached.messages.lastIndex)
                initialScrollDone = true
            }
        } catch (_: Exception) { }
        var firstFetch = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val hasCachedRemoteMessages = messages.any { !it.isPending }
                    if (firstFetch && !hasCachedRemoteMessages) {
                        val page = api.latestMessages(chat.id)
                        val pending = messages.filter { it.isPending }
                        messages = (page.messages + pending).distinctBy { it.id }
                            .sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
                        hasMoreOlder = page.hasMore
                        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
                        initialScrollDone = true
                    } else {
                        val loaded = messages.filterNot { it.isPending }
                        val afterId = loaded.mapNotNull { it.id.toLongOrNull() }
                            .maxOrNull()?.toString() ?: "0"
                        val statusIds = loaded.filter { it.direction == "outbound" }
                            .takeLast(50).map { it.id }
                        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                            ?.let { it >= messages.lastIndex - 1 } ?: true
                        val changed = api.syncMessages(
                            chat.id, afterId, statusIds, pendingServerIds.values.toSet()
                        )
                        mergeChangedMessages(changed, atBottom)
                    }
                    firstFetch = false
                    error = null
                } catch (e: ApiException) {
                    if (e.httpStatus == 401) { onUnauthorized(); return@repeatOnLifecycle }
                    error = e.message
                } catch (e: Exception) { error = e.message }
                delay(1000)
            }
        }
    }
    LaunchedEffect(listState, chat.id) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { firstVisible ->
            if (initialScrollDone && firstVisible <= 2 && hasMoreOlder && !loadingOlder) {
                val cursor = messages.firstOrNull { !it.isPending }?.id ?: return@collect
                val oldIndex = listState.firstVisibleItemIndex
                val oldOffset = listState.firstVisibleItemScrollOffset
                loadingOlder = true
                try {
                    val page = api.olderMessages(chat.id, cursor)
                    val additions = page.messages.filter { remote -> messages.none { it.id == remote.id } }
                    if (additions.isNotEmpty()) {
                        messages = (additions + messages).distinctBy { it.id }
                            .sortedBy { it.id.toLongOrNull() ?: Long.MAX_VALUE }
                        hasMoreOlder = page.hasMore
                        listState.scrollToItem(oldIndex + additions.size, oldOffset)
                    } else {
                        hasMoreOlder = false
                    }
                } catch (e: ApiException) {
                    if (e.httpStatus == 401) onUnauthorized() else error = e.message
                    hasMoreOlder = false
                } catch (e: Exception) {
                    error = e.message
                    hasMoreOlder = false
                } finally { loadingOlder = false }
            }
        }
    }
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        Row(Modifier.fillMaxWidth().background(Color(0xFF053D30)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar",tint=Color.White) }
            Box(Modifier.clickable{showInfo=true}){ Avatar(chat.name) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.name,fontWeight=FontWeight.SemiBold,color=Color.White,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(chat.phone,color=Color(0xFFA7DFC5),fontSize=12.sp)
            }
            IconButton(onClick={showTemplate=true}) {
                Icon(Icons.Default.Description,"Enviar template",tint=Color.White)
            }
            IconButton(onClick={showInfo=true}) {
                Icon(Icons.Default.Info,"Informações",tint=Color.White)
            }
        }
    }, bottomBar = {
        Column(Modifier.background(ChatBackground)) {
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp))
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensagem") }, shape = RoundedCornerShape(24.dp), maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White, unfocusedContainerColor = Color.White))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(enabled = !sending && input.isNotBlank(), onClick = {
                    val text = input.trim()
                    sending = true
                    val pendingId = "pending-${UUID.randomUUID()}"
                    val pending = ChatMessage(
                        id = pendingId, text = text, direction = "outbound",
                        time = LocalTime.now().toString().take(5), status = "pending", isPending = true
                    )
                    messages = messages + pending
                    scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                    scope.launch {
                        try {
                            api.cachePendingMessage(chat.id, pending)
                            val receipt = api.send(chat.id, text)
                            api.attachPendingMessageId(chat.id, pendingId, receipt.id)
                            pendingServerIds = pendingServerIds + (pendingId to receipt.id)
                            input = ""
                            error = null
                        } catch (e: Exception) {
                            try { api.removePendingMessage(chat.id, pendingId) } catch (_: Exception) { }
                            messages = messages.filterNot { it.id == pendingId }
                            error = e.message
                        }
                        finally { sending = false }
                    }
                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                }
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(ChatBackground)) {
            if (loadingOlder) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState,
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(message, api, onUnauthorized)
                }
            }
        }
    }
    if(showTemplate) ZapTemplateDialog(onDismiss={showTemplate=false})
    if(showInfo) ZapContactInfo(chat,onDismiss={showInfo=false})
}

@Composable
private fun ChatMessageBubble(message: ChatMessage, api: PonteApi, onUnauthorized: () -> Unit) {
    val outbound = message.direction == "outbound"
    if (message.isPending) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(LightGreen)
                .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Green)
                Text("Carregando mensagem…", color = Ink, fontSize = 13.sp)
                Text(message.time.takeLast(5), color = Color.Gray, fontSize = 10.sp)
            }
        }
        return
    }
    val kind = resolvedMediaKind(message)
    val titleAndIcon = when (kind) {
        "image" -> Icons.Default.Image to "Imagem"
        "audio" -> Icons.Default.MusicNote to "Áudio"
        "video" -> Icons.Default.Videocam to "Vídeo"
        "sticker" -> Icons.Default.EmojiEmotions to "Sticker"
        "document" -> Icons.Default.AttachFile to "Documento"
        else -> null
    }
    var attachment by remember(message.id) { mutableStateOf<MediaAttachment?>(null) }
    var loading by remember(message.id) { mutableStateOf(false) }
    var mediaError by remember(message.id) { mutableStateOf<String?>(null) }
    var retry by remember(message.id) { mutableIntStateOf(0) }
    LaunchedEffect(message.id, message.hasMedia, message.type, message.mediaMime, message.mediaFilename, retry) {
        if (message.hasMedia) {
            loading = true
            mediaError = null
            try {
                attachment = api.downloadMedia(message.id, message.mediaFilename, message.mediaMime)
            } catch (e: Exception) {
                if (e is ApiException && e.httpStatus == 401) onUnauthorized()
                mediaError = e.message ?: "Não foi possível baixar o anexo."
            } finally {
                loading = false
            }
        }
    }

    val body = message.text.trim()
    val mediaCaption = if (kind != null) mediaCaption(body, kind, message.mediaFilename) else body
    Row(Modifier.fillMaxWidth(),
        horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 305.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (outbound) LightGreen else Color.White)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (titleAndIcon != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(titleAndIcon.first, contentDescription = null, tint = Green,
                        modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(when (kind) {
                        "image" -> "Imagem recebida"
                        "audio" -> "Áudio recebido"
                        "video" -> "Vídeo recebido"
                        "sticker" -> "Sticker recebido"
                        else -> message.mediaFilename.ifBlank { "Documento recebido" }
                    }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                when {
                    !message.hasMedia -> Text("Arquivo de mídia indisponível nesta mensagem.",
                        color = Color.Gray, fontSize = 12.sp)
                    loading -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Carregando anexo…", color = Color.Gray, fontSize = 12.sp)
                    }
                    mediaError != null -> Column {
                        Text(mediaError.orEmpty(), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        TextButton(onClick = { retry++ }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("Tentar novamente")
                        }
                    }
                    attachment != null -> when (kind) {
                        "image", "sticker" -> ImageMessageContent(attachment!!, kind == "sticker")
                        "audio" -> AudioMessageContent(attachment!!)
                        "video" -> VideoMessageContent(attachment!!)
                        else -> OpenDocumentButton(attachment!!)
                    }
                }
                if (mediaCaption.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(mediaCaption, color = Ink, fontSize = 14.sp)
                }
            } else {
                Text(body.ifBlank { "[Mensagem sem texto]" }, color = Ink, fontSize = 15.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(message.time.takeLast(5), color = Color.Gray, fontSize = 10.sp)
                if (outbound) {
                    Spacer(Modifier.width(5.dp))
                    Text(when (message.status) {
                        "read" -> "✓✓"
                        "delivered" -> "✓✓"
                        else -> "✓"
                    }, color = if (message.status == "read") Green else Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun mediaCaption(body: String, kind: String, filename: String): String {
    var caption = body.trim()
    if (caption.isBlank()) return ""
    if (filename.isNotBlank() && caption.equals("Anexo: $filename", ignoreCase = true)) return ""
    if (caption.startsWith("Anexo recebido:", ignoreCase = true)) {
        val detail = caption.substringAfter(':').trim()
        if (detail.equals(kind, ignoreCase = true) || detail.isBlank()) return ""
        caption = detail
    }
    val prefixes = listOf(
        "[$kind]", "[${kind.replaceFirstChar { it.uppercase() }}]",
        "[Mídia $kind]", "[Mídia recebida: $kind]"
    )
    val prefix = prefixes.firstOrNull { caption.startsWith(it, ignoreCase = true) }
    if (prefix != null) return caption.drop(prefix.length).trim()
    caption = Regex("^\\[m[ií]dia[^]]*]\\s*", RegexOption.IGNORE_CASE).replaceFirst(caption, "")
    if (caption.startsWith("Anexo:", ignoreCase = true)) return ""
    return caption
}

private fun resolvedMediaKind(message: ChatMessage): String? {
    val type = message.type.lowercase()
    if (type in setOf("image", "audio", "video", "sticker", "document")) return type
    val mime = message.mediaMime.lowercase()
    return when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("video/") -> "video"
        mime.isNotBlank() -> "document"
        message.hasMedia -> "document"
        else -> null
    }
}

@Composable
private fun ImageMessageContent(attachment: MediaAttachment, sticker: Boolean) {
    var image by remember(attachment.file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(attachment.file.absolutePath) {
        image = withContext(Dispatchers.IO) { decodeMessageImage(attachment.file) }
        failed = image == null
    }
    when {
        image != null -> Image(image!!,
            contentDescription = if (sticker) "Sticker recebido" else "Imagem recebida",
            contentScale = ContentScale.Fit,
            modifier = if (sticker) Modifier.sizeIn(maxWidth = 190.dp, maxHeight = 190.dp)
                else Modifier.fillMaxWidth().heightIn(max = 280.dp))
        failed -> Text("Não foi possível abrir esta imagem.", color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp)
        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun decodeMessageImage(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val maxDimension = 1800
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun AudioMessageContent(attachment: MediaAttachment) {
    val player = remember(attachment.file.absolutePath) { MediaPlayer() }
    var prepared by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    var playing by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    var failed by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    var duration by remember(attachment.file.absolutePath) { mutableIntStateOf(0) }
    var position by remember(attachment.file.absolutePath) { mutableIntStateOf(0) }
    DisposableEffect(player, attachment.file.absolutePath) {
        try {
            player.setOnPreparedListener {
                duration = it.duration.coerceAtLeast(0)
                prepared = true
            }
            player.setOnCompletionListener {
                playing = false
                position = 0
                it.seekTo(0)
            }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                playing = false
                true
            }
            player.setDataSource(attachment.file.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }
        onDispose { try { player.release() } catch (_: Exception) { } }
    }
    LaunchedEffect(playing, prepared, failed) {
        while (playing && prepared && !failed) {
            try { position = player.currentPosition } catch (_: Exception) { playing = false }
            delay(250)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FilledIconButton(enabled = prepared && !failed, onClick = {
            try {
                if (playing) { player.pause(); playing = false }
                else { player.start(); playing = true }
            } catch (_: Exception) { failed = true }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pausar áudio" else "Reproduzir áudio")
        }
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            onValueChange = { position = it.toInt() },
            onValueChangeFinished = { try { player.seekTo(position) } catch (_: Exception) { } },
            enabled = prepared && !failed, modifier = Modifier.weight(1f))
        Text(formatMediaTime(position), fontSize = 10.sp, color = Color.Gray)
    }
    if (failed) Text("O Android não conseguiu reproduzir este áudio.",
        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
}

@Composable
private fun VideoMessageContent(attachment: MediaAttachment) {
    var view by remember(attachment.file.absolutePath) { mutableStateOf<VideoView?>(null) }
    var prepared by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    var playing by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    var failed by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    DisposableEffect(attachment.file.absolutePath) {
        onDispose { try { view?.stopPlayback() } catch (_: Exception) { } }
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener {
                        prepared = true
                        failed = false
                    }
                    setOnCompletionListener { playing = false }
                    setOnErrorListener { _, _, _ ->
                        failed = true
                        prepared = false
                        playing = false
                        true
                    }
                    setVideoURI(Uri.fromFile(attachment.file))
                    view = this
                }
            },
            modifier = Modifier.matchParentSize()
        )
        when {
            failed -> Text("O Android não conseguiu reproduzir este vídeo.",
                color = Color.White, fontSize = 12.sp)
            !prepared -> CircularProgressIndicator(color = Color.White)
            !playing -> IconButton(
                onClick = {
                    try {
                        view?.start()
                        playing = true
                    } catch (_: Exception) {
                        failed = true
                    }
                },
                modifier = Modifier.size(64.dp).background(Green.copy(alpha = 0.92f), CircleShape)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproduzir vídeo",
                    tint = Color.White, modifier = Modifier.size(38.dp))
            }
        }
    }
}

@Composable
private fun OpenDocumentButton(attachment: MediaAttachment) {
    val context = LocalContext.current
    var error by remember(attachment.file.absolutePath) { mutableStateOf(false) }
    OutlinedButton(onClick = {
        try {
            val uri = FileProvider.getUriForFile(context,
                "${BuildConfig.APPLICATION_ID}.fileprovider", attachment.file)
            val mime = attachment.mimeType.ifBlank { "application/octet-stream" }
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Abrir anexo"))
            error = false
        } catch (_: ActivityNotFoundException) {
            error = true
            Toast.makeText(context, "Não há aplicativo para abrir este arquivo.", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            error = true
        }
    }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Abrir ${attachment.filename.ifBlank { "anexo" }}", maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
    if (error) Text("Não foi possível abrir o arquivo.", color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp)
}

private fun formatMediaTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
