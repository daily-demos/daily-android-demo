package co.daily.core.dailydemo

import co.daily.model.MediaState
import co.daily.model.ParticipantVideoInfo

object Utils {

    fun isMediaAvailable(info: ParticipantVideoInfo?): Boolean {
        return when (info?.state) {
            MediaState.blocked, MediaState.off, MediaState.interrupted -> false
            MediaState.receivable, MediaState.loading, MediaState.playable -> true
            null -> false
        }
    }
}
