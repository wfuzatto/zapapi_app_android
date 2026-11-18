package br.com.prodatastelecom.zapapi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ZapGreen=Color(0xFF087B61)
private val ZapInk=Color(0xFF193D31)

@Composable
fun ZapExtras(tab:Int,api:PonteApi,onChat:(ChatSummary)->Unit,onSettings:()->Unit) {
    var conversations by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) {
        error=null
        if (tab == 1) {
            loading=true
            try { conversations=api.conversations() }
            catch(e:Exception) { error=e.message }
            finally { loading=false }
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6FAF7)).padding(16.dp)) {
        if(error!=null) Text(error.orEmpty(),color=MaterialTheme.colorScheme.error)
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when(tab) {
            1 -> {
                Text("Contatos",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ZapInk)
                Text("Contatos com conversas registradas",color=Color.Gray,fontSize=12.sp)
                Spacer(Modifier.height(14.dp))
                if(!loading && error==null && conversations.isEmpty())
                    EmptyZap("Nenhum contato","As conversas recebidas aparecerão aqui.")
                LazyColumn {
                    items(conversations,key={it.id}) { item ->
                        Row(Modifier.fillMaxWidth().clickable { onChat(item) }
                            .padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
                            ZapInitial(item.name)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name,color=ZapInk,fontWeight=FontWeight.SemiBold)
                                Text("+"+item.phone,color=Color.Gray,fontSize=12.sp)
                            }
                            Icon(Icons.Default.ChevronRight,null,tint=Color.Gray)
                        }
                        HorizontalDivider(color=Color(0xFFE3ECE7))
                    }
                }
            }
            2 -> {
                Text("Templates oficiais",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ZapInk)
                Text("Modelos aprovados pela Meta",color=Color.Gray,fontSize=12.sp)
                Spacer(Modifier.height(14.dp))
                EmptyZap("Use o painel web para templates",
                    "A API móvel envia texto na janela de atendimento; o app ainda não oferece envio de templates.")
            }
            else -> {
                Text("Configurações",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ZapInk)
                Spacer(Modifier.height(14.dp))
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),
                    shape=RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser,null,tint=ZapGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("ZapApi Messenger",fontWeight=FontWeight.Bold,color=ZapInk)
                        }
                        Text("Conexão segura com seu tenant.",color=Color.Gray,fontSize=13.sp)
                        HorizontalDivider(Modifier.padding(vertical=12.dp))
                        Text("O app móvel usa o número conectado ao tenant.")
                        Spacer(Modifier.height(13.dp))
                        OutlinedButton(onClick=onSettings,modifier=Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Settings,null)
                            Spacer(Modifier.width(7.dp))
                            Text("Servidor e endpoints")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Firebase preparado. O push requer configuração de envio FCM no backend.",
                    color=Color.Gray,fontSize=12.sp)
            }
        }
    }
}

@Composable
private fun ZapInitial(name:String) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFDAF3E5)),
        contentAlignment=Alignment.Center) {
        Text(name.take(1).uppercase(),color=ZapGreen,fontWeight=FontWeight.Bold,fontSize=20.sp)
    }
}

@Composable
private fun EmptyZap(title:String,subtitle:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=42.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.Forum,null,tint=ZapGreen,modifier=Modifier.size(42.dp))
        Spacer(Modifier.height(9.dp))
        Text(title,fontWeight=FontWeight.SemiBold,color=ZapInk)
        Text(subtitle,color=Color.Gray,fontSize=12.sp)
    }
}

@Composable
fun ZapTemplateDialog(onDismiss:()->Unit) {
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Templates oficiais")},
        text={Text("O app móvel ainda não oferece envio de templates. Use o painel web para iniciar conversas com um template aprovado.")},
        confirmButton={TextButton(onClick=onDismiss){Text("Entendi")}}
    )
}

@Composable
fun ZapContactInfo(chat:ChatSummary,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Dados do contato")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(chat.name,fontSize=18.sp,fontWeight=FontWeight.Bold)
            Text("+"+chat.phone)
            HorizontalDivider()
            Text("Contato da conta Meta vinculada ao seu tenant.",
                color=Color.Gray,fontSize=12.sp)
            Text("Etiquetas e dados de reservas serão integrados futuramente.",
                color=Color.Gray,fontSize=12.sp)
        }},
        confirmButton={TextButton(onClick=onDismiss){Text("Fechar")}}
    )
}
