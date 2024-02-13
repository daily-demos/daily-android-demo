package co.daily.core.dailydemo.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import co.daily.core.dailydemo.DemoState
import co.daily.core.dailydemo.DemoStateListener
import co.daily.core.dailydemo.MainActivity
import co.daily.core.dailydemo.R

abstract class ForegroundService(
    private val tag: String,
    private val notificationChannelId: String,
    private val notificationChannelName: String,
) : Service(), DemoStateListener {

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(tag, "Connected to service")
            callService = service!! as DemoCallService.Binder
            callService?.addListener(this@ForegroundService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(tag, "Disconnected from service")
            callService = null
        }
    }

    private lateinit var notification: Notification

    private var callService: DemoCallService.Binder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        Log.i(tag, "onCreate()")
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                notificationChannelName,
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val leaveIntent = PendingIntent.getService(
            this,
            0,
            DemoCallService.leaveIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )

        notification = NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(R.drawable.video)
            .setContentTitle(resources.getString(R.string.service_notification_title))
            .setContentText(resources.getString(R.string.service_notification_message))
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action(
                    0,
                    resources.getString(R.string.hangup_button),
                    leaveIntent
                )
            )
            .build()

        if (!bindService(
                Intent(
                        this,
                        DemoCallService::class.java
                    ),
                serviceConnection, BIND_AUTO_CREATE or BIND_IMPORTANT
            )
        ) {
            throw RuntimeException("Failed to bind to call service")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(tag, "onStartCommand()")
        startForeground(1, notification)
        return START_STICKY
    }

    abstract override fun onStateChanged(newState: DemoState)

    override fun onDestroy() {
        super.onDestroy()
        callService?.removeListener(this)
        unbindService(serviceConnection)
    }
}
