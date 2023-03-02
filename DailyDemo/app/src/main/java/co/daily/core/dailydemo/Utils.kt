package co.daily.core.dailydemo

import android.os.Handler
import android.os.Looper
import co.daily.model.MediaState
import co.daily.model.ParticipantVideoInfo

object Utils {

    val UI_THREAD_HANDLER = Handler(Looper.getMainLooper())

    fun isMediaAvailable(info: ParticipantVideoInfo?): Boolean {
        return when (info?.state) {
            MediaState.blocked, MediaState.off, MediaState.interrupted -> false
            MediaState.receivable, MediaState.loading, MediaState.playable -> true
            null -> false
        }
    }
}
