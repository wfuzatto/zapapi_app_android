package br.com.prodatastelecom.zapapi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

class PonteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sound = Uri.parse("android.resource://$packageName/raw/viber")
            val soundAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                PonteMessagingService.MESSAGE_CHANNEL_ID,
                "Mensagens do ZapApi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificações de novas mensagens do ZapApi"
                setSound(sound, soundAttributes)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                channel
            )
        }
    }
}
