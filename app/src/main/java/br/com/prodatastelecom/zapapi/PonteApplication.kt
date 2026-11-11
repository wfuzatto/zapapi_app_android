package br.com.prodatastelecom.zapapi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PonteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("messages", "Novas mensagens", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }
}
