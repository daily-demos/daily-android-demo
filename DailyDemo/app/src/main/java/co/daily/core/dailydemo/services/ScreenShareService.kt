package co.daily.core.dailydemo.services

import android.util.Log
import co.daily.core.dailydemo.DemoState
import co.daily.model.CallState

private const val TAG = "ScreenShareService"

class ScreenShareService : ForegroundService(
    tag = TAG,
    notificationChannelId = "channel_screenshare",
    notificationChannelName = "Active screen share",
) {
    override fun onStateChanged(newState: DemoState) {
        if (newState.status != CallState.joined) {
            Log.i(TAG, "No longer in a call! Shutting down")
            stopSelf()
        } else if (!newState.screenShareActive) {
            Log.i(TAG, "No longer sharing screen! Shutting down")
            stopSelf()
        }
    }
}
