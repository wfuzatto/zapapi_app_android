package br.com.prodatastelecom.zapapi

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * O push contém apenas o nome do contato e uma prévia curta; o app consulta a mensagem
 * completa pela API autenticada quando a conversa é aberta.
 */
class PonteMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = SecureSession(this)
        if (session.read() == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try { PonteApi(session, ServerConfigStore(this@PonteMessagingService)).registerDevice(token) } catch (_: Exception) { /* sincroniza no próximo login */ }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // O backend impõe o tenant e só envia uma prévia, nunca o conteúdo completo da conversa.
        val type = message.data["type"] ?: return
        if (type != "new_message") return
        if (SecureSession(this).read() == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sender = message.data["sender"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title?.takeIf { it.isNotBlank() }
            ?: "ZapApi"
        val preview = message.data["preview"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body?.takeIf { it.isNotBlank() }
            ?: "Nova mensagem recebida."
        val notification = NotificationCompat.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_stat_zapapi)
            .setColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            .setContentTitle(sender)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}

