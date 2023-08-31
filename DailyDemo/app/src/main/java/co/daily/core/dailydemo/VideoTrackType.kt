package co.daily.core.dailydemo

import co.daily.model.customtrack.CustomTrackName

sealed interface VideoTrackType {
    object Camera : VideoTrackType
    object ScreenShare : VideoTrackType
    data class CustomTrack(val name: CustomTrackName) : VideoTrackType
}
