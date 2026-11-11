package br.com.prodatastelecom.zapapi

import androidx.compose.foundation.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val ZapGreen=Color(0xFF087B61)
private val ZapInk=Color(0xFF193D31)

@Composable
fun ZapExtras(tab:Int,api:PonteApi,onChat:(ChatSummary)->Unit,onSettings:()->Unit) {
    val scope=rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<ZapAccount>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ZapContact>>(emptyList()) }
    var templates by remember { mutableStateOf<List<ZapTemplate>>(emptyList()) }
    var activeAccount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) {
        loading=true
        try {
            accounts=api.accounts()
            if(activeAccount==0 && accounts.isNotEmpty())activeAccount=accounts.first().id
            when(tab) {
                1 -> contacts=api.contacts()
                2 -> if(activeAccount>0)templates=api.templates(activeAccount)
            }
            error=null
        } catch(e:Exception) { error=e.message }
        loading=false
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6FAF7)).padding(16.dp)) {
        if(error!=null) Text(error.orEmpty(),color=MaterialTheme.colorScheme.error)
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when(tab) {
            1 -> {
                Text("Contatos",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ZapInk)
                Text("Contatos do seu ambiente ZapApi",color=Color.Gray,fontSize=12.sp)
                Spacer(Modifier.height(14.dp))
                if(!loading&&contacts.isEmpty()) EmptyZap("Nenhum contato","As mensagens recebidas preencherão a lista.")
                LazyColumn {
                    items(contacts,key={it.accountId.toString()+"-"+it.phone}) { item ->
                        Row(Modifier.fillMaxWidth().clickable {
                            onChat(ChatSummary(item.accountId.toString()+"-"+item.phone,
                                item.name,item.phone,"","",0))
                        }.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
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
                if(accounts.size>1) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick={expanded=true}){
                            Text(accounts.find{it.id==activeAccount}?.name?:"Selecionar número")
                            Icon(Icons.Default.ArrowDropDown,null)
                        }
                        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
                            accounts.forEach { a ->
                                DropdownMenuItem(text={Text(a.name)},onClick={
                                    expanded=false;activeAccount=a.id
                                    scope.launch {
                                        try{templates=api.templates(a.id);error=null}
                                        catch(e:Exception){error=e.message}
                                    }
                                })
                            }
                        }
                    }
                }
                if(!loading&&accounts.isEmpty())
                    EmptyZap("Nenhum número conectado","Cadastre as credenciais Meta no portal web.")
                else if(!loading&&templates.isEmpty())
                    EmptyZap("Nenhum template aprovado","Envie templates para análise em zapapi.inf.br.")
                else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    items(templates,key={it.name+"-"+it.language+"-"+it.accountId}) { t ->
                        Card(colors=CardDefaults.cardColors(containerColor=Color.White),
                            border=BorderStroke(1.dp,Color(0xFFD9E9E0)),
                            shape=RoundedCornerShape(14.dp)){
                            Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){
                                Icon(Icons.Default.Description,null,tint=ZapGreen)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(t.name,fontWeight=FontWeight.SemiBold,color=ZapInk)
                                    Text((t.header+" · "+t.body).trim().ifBlank{"Modelo aprovado"},fontSize=12.sp,color=Color.Gray,
                                        maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(t.language+" · "+t.category+" · APROVADO",fontSize=11.sp,color=ZapGreen)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text("Para enviar, abra uma conversa e selecione o ícone de modelo.",
                    color=Color.Gray,fontSize=12.sp)
            }
            else -> {
                Text("Configurações",fontSize=22.sp,fontWeight=FontWeight.Bold,color=ZapInk)
                Spacer(Modifier.height(14.dp))
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),
                    shape=RoundedCornerShape(16.dp)){
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Icon(Icons.Default.VerifiedUser,null,tint=ZapGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("ZapApi Messenger",fontWeight=FontWeight.Bold,color=ZapInk)
                        }
                        Text("Conexão segura com seu tenant.",color=Color.Gray,fontSize=13.sp)
                        HorizontalDivider(Modifier.padding(vertical=12.dp))
                        Text("Números conectados: "+accounts.size)
                        Spacer(Modifier.height(13.dp))
                        OutlinedButton(onClick=onSettings,modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Default.Settings,null)
                            Spacer(Modifier.width(7.dp))
                            Text("Servidor e endpoints")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Firebase preparado. O push requer a configuração de envio FCM no backend.",
                    color=Color.Gray,fontSize=12.sp)
            }
        }
    }
}
@Composable
private fun ZapInitial(name:String) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFDAF3E5)),
        contentAlignment=Alignment.Center){
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
fun ZapTemplateDialog(chat:ChatSummary,api:PonteApi,onDismiss:()->Unit,onSent:()->Unit) {
    val scope=rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<ZapTemplate>>(emptyList()) }
    var selected by remember { mutableStateOf<ZapTemplate?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf<List<String>>(emptyList()) }
    var headerValues by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(chat.id) {
        try {
            templates=api.templates(chat.id.substringBefore('-').toIntOrNull()?:0)
            selected=templates.firstOrNull()
            values=List(selected?.variables?:0){""}
            headerValues=List(selected?.headerVariables?:0){""}
        } catch(e:Exception){error=e.message}
        loading=false
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Template aprovado")},
        text={
            Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())) {
                Text("Fora da janela de 24 horas é obrigatório usar template aprovado.",
                    fontSize=12.sp,color=Color.Gray)
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(error!=null)Text(error.orEmpty(),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                if(!loading&&templates.isEmpty())Text("Não há modelos aprovados para este número.")
                else{
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){
                            Text(selected?.name?:"Selecionar",modifier=Modifier.weight(1f),maxLines=1)
                            Icon(Icons.Default.ArrowDropDown,null)
                        }
                        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                            templates.forEach{ t ->
                                DropdownMenuItem(text={Text(t.name+" · "+t.language)},onClick={
                                    selected=t;values=List(t.variables){""}
                                    headerValues=List(t.headerVariables){""};expanded=false
                                })
                            }
                        }
                    }
                    selected?.let{ t ->
                        if(t.header.isNotBlank())
                            Text(t.header,color=ZapInk,fontWeight=FontWeight.Bold,fontSize=13.sp)
                        Text(t.body,color=ZapInk,fontSize=13.sp,modifier=Modifier.padding(vertical=9.dp))
                        repeat(t.headerVariables){i ->
                            OutlinedTextField(value=headerValues.getOrNull(i)?:"",
                                onValueChange={v->headerValues=headerValues.toMutableList().also{it[i]=v}},
                                label={Text("Cabeçalho "+(i+1))},
                                singleLine=true,modifier=Modifier.fillMaxWidth())
                        }
                        repeat(t.variables){i ->
                            OutlinedTextField(value=values.getOrNull(i)?:"",
                                onValueChange={v->values=values.toMutableList().also{it[i]=v}},
                                label={Text("Variável "+(i+1))},
                                singleLine=true,modifier=Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton={
            TextButton(enabled=selected!=null&&!loading&&!sending&&values.all{it.isNotBlank()}&&headerValues.all{it.isNotBlank()},
                onClick={
                    val t=selected?:return@TextButton
                    sending=true
                    scope.launch{
                        try{api.sendTemplate(chat.id,t,values,headerValues);onSent()}
                        catch(e:Exception){error=e.message}
                        finally{sending=false}
                    }
                }){Text(if(sending)"Enviando..." else "Enviar")}
        },
        dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}}
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
