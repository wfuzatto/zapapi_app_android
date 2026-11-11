package br.com.prodatastelecom.zapapi

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Green = Color(0xFF087F68)
private val LightGreen = Color(0xFFE7F6EE)
private val ChatBackground = Color(0xFFF5F6F2)
private val Ink = Color(0xFF132722)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SecureSession(this)
        val configs = ServerConfigStore(this)
        val api = PonteApi(session, configs)
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
                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    if (!isLoggedIn) {
                        LoginScreen(api, session, onLogin = { isLoggedIn = true },
                            onSettings = { showSettings = true })
                    } else if (activeChat != null) {
                        val chat = activeChat!!
                        ChatScreen(chat, api, onBack = { activeChat = null }, onUnauthorized = {
                            session.clear()
                            isLoggedIn = false
                            activeChat = null
                        })
                    } else {
                        HomeScreen(api, refreshKey, onChat = { activeChat = it },
                            onRefresh = { refreshKey++ },
                            onSettings = { showSettings = true },
                            onLogout = {
                                scope.launch {
                                    try { api.logout() } catch (_: Exception) { session.clear() }
                                    isLoggedIn = false
                                }
                            }, onUnauthorized = {
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
                    val token = api.login(email.trim(), password)
                    session.save(token)
                    onLogin()
                } catch (e: Exception) { error = e.message ?: "Não foi possível conectar." }
                finally { busy = false }
            }
        }, enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
            else Text("Entrar")
        }
        Spacer(Modifier.height(15.dp))
        Text("Entre com sua conta ZapApi. O token da Meta permanece protegido no servidor.",
            fontSize = 12.sp, color = Color.Gray)
    }
    IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
        Icon(Icons.Default.Settings, contentDescription = "Configurações do servidor", tint = Green)
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(api: PonteApi, refreshKey: Int, onChat: (ChatSummary) -> Unit,
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
    var accounts by remember { mutableStateOf<List<ZapAccount>>(emptyList()) }
    var accountSelected by remember { mutableIntStateOf(0) }
    LaunchedEffect(newChat) {
        if(newChat)try{
            accounts=api.accounts()
            accountSelected=accounts.firstOrNull()?.id ?: 0
        }catch(e:Exception){error=e.message}
    }
    LaunchedEffect(refreshKey) {
        while (isActive) {
            try {
                chats = api.conversations()
                error = null
            } catch (e: ApiException) {
                if (e.httpStatus == 401) { onUnauthorized(); return@LaunchedEffect }
                error = e.message
            } catch (e: Exception) { error = e.message }
            loading = false
            delay(12000)
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
                shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
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
            val icons=listOf(Icons.Default.Chat,Icons.Default.People,
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
                    if(accounts.size>1) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick={expanded=true}){
                                Text(accounts.find{it.id==accountSelected}?.name?:"Selecionar número")
                                Icon(Icons.Default.ArrowDropDown,null)
                            }
                            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                                accounts.forEach { a ->
                                    DropdownMenuItem(text={Text(a.name)},onClick={
                                        accountSelected=a.id;expanded=false
                                    })
                                }
                            }
                        }
                    }
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
                            val id = if(accountSelected>0)api.createConversation(newPhone,accountSelected)
                                else api.createConversation(newPhone)
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
private fun ChatScreen(chat: ChatSummary, api: PonteApi, onBack: () -> Unit, onUnauthorized: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember(chat.id) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember(chat.id) { mutableStateOf("") }
    var error by remember(chat.id) { mutableStateOf<String?>(null) }
    var sending by remember(chat.id) { mutableStateOf(false) }
    var showTemplate by remember(chat.id) { mutableStateOf(false) }
    var showInfo by remember(chat.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(chat.id) {
        while (isActive) {
            try {
                messages = api.messages(chat.id)
                error = null
            } catch (e: ApiException) {
                if (e.httpStatus == 401) { onUnauthorized(); return@LaunchedEffect }
                error = e.message
            } catch (e: Exception) { error = e.message }
            delay(6000)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Scaffold(topBar = {
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
                    scope.launch {
                        try {
                            api.send(chat.id, text)
                            input = ""
                            messages = api.messages(chat.id)
                            error = null
                        } catch (e: Exception) { error = e.message }
                        finally { sending = false }
                    }
                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().background(ChatBackground)
            .padding(horizontal = 12.dp), state = listState,
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message ->
                val outbound = message.direction == "outbound"
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 305.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (outbound) LightGreen else Color.White)
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(message.text.ifBlank { "[Mensagem sem texto]" }, color = Ink, fontSize = 15.sp)
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
        }
    }
    if(showTemplate) {
        ZapTemplateDialog(chat,api,onDismiss={showTemplate=false},onSent={
            showTemplate=false
            scope.launch{try{messages=api.messages(chat.id)}catch(_:Exception){}}
        })
    }
    if(showInfo) ZapContactInfo(chat,onDismiss={showInfo=false})
}
