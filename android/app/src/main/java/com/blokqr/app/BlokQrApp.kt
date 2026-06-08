package com.blokqr.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BlokQrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    "qr_analysis", "Analyses BlokQR",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
