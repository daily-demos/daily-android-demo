package co.daily.core.dailydemo.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
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
import co.daily.model.CallState

private const val TAG = "ActiveCallService"

private const val NOTIFICATION_CHANNEL_CALL = "channel_call"

class DemoActiveCallService : Service(), DemoStateListener {

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to service")
            callService = service!! as DemoCallService.Binder
            callService?.addListener(this@DemoActiveCallService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Disconnected from service")
            callService = null
        }
    }

    private lateinit var notification: Notification

    private var callService: DemoCallService.Binder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        Log.i(TAG, "onCreate()")
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_CALL,
                "Active call",
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

        notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_CALL)
            .setSmallIcon(R.drawable.video)
            .setContentTitle(resources.getString(R.string.service_notification_title))
            .setContentText(resources.getString(R.string.service_notification_message))
            .setOngoing(true)
            .setUsesChronometer(true)
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
                serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        ) {
            throw RuntimeException("Failed to bind to call service")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand()")
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onStateChanged(newState: DemoState) {
        if (newState.status != CallState.joined) {
            Log.i(TAG, "No longer in a call! Shutting down")
            stopSelf()
        }
    }

    override fun onError(msg: String) {
        // Nothing to do here
    }

    override fun onDestroy() {
        super.onDestroy()
        callService?.removeListener(this)
        unbindService(serviceConnection)
    }
}
