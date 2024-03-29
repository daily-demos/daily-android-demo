package co.daily.core.dailydemo.services

import android.util.Log
import co.daily.core.dailydemo.DemoState
import co.daily.model.CallState

private const val TAG = "ActiveCallService"

class DemoActiveCallService : ForegroundService(
    tag = TAG,
    notificationChannelId = "channel_call",
    notificationChannelName = "Active call",
) {
    override fun onStateChanged(newState: DemoState) {
        if (newState.status != CallState.joined) {
            Log.i(TAG, "No longer in a call! Shutting down")
            stopSelf()
        }
    }
}
