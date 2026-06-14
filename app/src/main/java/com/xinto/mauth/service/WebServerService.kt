package com.xinto.mauth.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.xinto.mauth.Mauth
import com.xinto.mauth.R
import com.xinto.mauth.core.webserver.WebServerManager
import org.koin.android.ext.android.inject

class WebServerService : Service() {

    private val webServerManager: WebServerManager by inject()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: ""

        webServerManager.start(port, token.ifBlank { null })

        val url = webServerManager.getLocalAddress() ?: "http://localhost:$port"
        val notification = buildNotification(url)
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onDestroy() {
        webServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.webserver_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.webserver_notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(url: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.webserver_notification_title))
            .setContentText(url)
            .setSmallIcon(R.drawable.ic_globe)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mauth_webserver"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        const val ACTION_STOP = "com.xinto.mauth.action.STOP_WEBSERVER"

        fun startIntent(
            mauth: Mauth,
            port: Int = 8080,
            token: String? = null
        ): Intent {
            return Intent(mauth, WebServerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
                if (token != null) putExtra(EXTRA_TOKEN, token)
            }
        }
    }
}
