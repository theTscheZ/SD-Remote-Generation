package com.a1111remote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service keeps the active Android process eligible to finish the ongoing
 * AUTOMATIC1111 request and progress polling while the Activity is in background.
 *
 * Generation still belongs to RemoteViewModel. If the system force-stops the entire
 * process, a new connection must be made to inspect the remote PC's current status.
 */
class GenerationStatusService : Service() {
    companion object {
        private const val CHANNEL = "a1111_generating"
        private const val NOTIFICATION_ID = 42000
        private const val START = "com.a1111remote.START_GENERATION"
        private const val UPDATE = "com.a1111remote.UPDATE_GENERATION"
        private const val FINISH = "com.a1111remote.FINISH_GENERATION"
        private const val EXTRA_PERCENT = "percent"
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: String) {
            val intent = Intent(context, GenerationStatusService::class.java)
                .setAction(START).putExtra(EXTRA_MODE, mode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(context: Context, progress: Float) {
            val intent = Intent(context, GenerationStatusService::class.java)
                .setAction(UPDATE)
                .putExtra(EXTRA_PERCENT, (progress * 100f).toInt().coerceIn(0, 100))
            context.startService(intent)
        }

        fun finish(context: Context) {
            context.startService(Intent(context, GenerationStatusService::class.java)
                .setAction(FINISH))
        }
    }

    private var mode = "txt2img"
    private var percent = 0
    private var active = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "A1111 Generierung", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Fortschritt laufender PC-Generierungen" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                mode = intent.getStringExtra(EXTRA_MODE) ?: "txt2img"
                percent = 0
                active = true
                startForeground(NOTIFICATION_ID, notification())
            }
            UPDATE -> {
                if (active) {
                    percent = intent.getIntExtra(EXTRA_PERCENT, 0).coerceIn(0, 100)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification())
                }
            }
            FINISH -> {
                active = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val extras = mode == "Extras"
        val status = if (extras) "Bild wird auf dem PC verarbeitet"
            else "PC-Generierung: ${percent}%"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("A1111 Remote · $mode")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent, extras)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
