package br.com.prodatastelecom.zapapi

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Mantém uma notificação atualizada por conversa. O cursor de leitura sincronizado
 * com o servidor impede que um push atrasado faça reaparecer uma conversa já lida.
 */
class PonteMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = SecureSession(this)
        if (session.read() == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try { PonteApi(session, ServerConfigStore(this@PonteMessagingService)).registerDevice(token) }
            catch (_: Exception) { /* sincroniza no próximo login */ }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: return
        val conversationId = data["conversation_id"]?.takeIf { it.isNotBlank() } ?: return

        if (type == "conversation_read") {
            val through = data["read_through_id"]?.toLongOrNull() ?: return
            applyReadThrough(this, conversationId, through)
            return
        }
        if (type != "new_message" || SecureSession(this).read() == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val messageId = data["message_id"]?.toLongOrNull()?.takeIf { it > 0L } ?: return
        val prefs = notificationState(this)
        val key = stateKey("cursor", conversationId)
        val notifiedKey = stateKey("notified", conversationId)
        if (messageId <= prefs.getLong(key, 0L) || messageId <= prefs.getLong(notifiedKey, 0L)) return

        val sender = data["sender"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title?.takeIf { it.isNotBlank() }
            ?: "ZapApi"
        val preview = data["preview"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body?.takeIf { it.isNotBlank() }
            ?: "Nova mensagem recebida."
        val tag = notificationTag(conversationId)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversation_id", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(this, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zapapi)
            .setColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            .setContentTitle(sender)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        prefs.edit().putLong(notifiedKey, messageId).apply()
        NotificationManagerCompat.from(this).notify(tag, NOTIFICATION_ID, notification)
    }

    companion object {
        private const val STATE_PREFS = "zapapi_notification_read_state"
        private const val NOTIFICATION_ID = 0
        private const val LEGACY_NOTIFICATIONS_MIGRATED = "legacy_notifications_migrated"
        const val MESSAGE_CHANNEL_ID = "messages_viber"

        private fun notificationState(context: Context) =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun stateKey(kind: String, conversationId: String) = "$kind:${digest(conversationId)}"
        private fun notificationTag(conversationId: String) = "zapapi:${digest(conversationId)}"

        fun applyReadThrough(context: Context, conversationId: String, through: Long) {
            if (conversationId.isBlank() || through <= 0L) return
            val prefs = notificationState(context)
            val cursorKey = stateKey("cursor", conversationId)
            val notifiedKey = stateKey("notified", conversationId)
            val cursor = maxOf(prefs.getLong(cursorKey, 0L), through)
            val notified = prefs.getLong(notifiedKey, 0L)
            val editor = prefs.edit().putLong(cursorKey, cursor)
            if (notified == 0L || notified <= cursor) {
                NotificationManagerCompat.from(context).cancel(notificationTag(conversationId), NOTIFICATION_ID)
                if (notified > 0L) editor.remove(notifiedKey)
            }
            editor.apply()
        }

        fun reconcileReadCursors(context: Context, conversations: List<ChatSummary>) {
            val prefs = notificationState(context)
            if (!prefs.getBoolean(LEGACY_NOTIFICATIONS_MIGRATED, false)) {
                val manager = NotificationManagerCompat.from(context)
                manager.activeNotifications
                    .filter { it.tag == null && it.notification.channelId == "messages" }
                    .forEach { manager.cancel(it.tag, it.id) }
                prefs.edit().putBoolean(LEGACY_NOTIFICATIONS_MIGRATED, true).apply()
            }
            conversations.forEach { chat ->
                applyReadThrough(context, chat.id, chat.readThroughId)
            }
        }
    }
}
