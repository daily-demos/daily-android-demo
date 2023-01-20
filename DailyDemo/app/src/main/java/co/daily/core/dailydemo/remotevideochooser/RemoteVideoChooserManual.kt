package co.daily.core.dailydemo.remotevideochooser

import co.daily.core.dailydemo.Utils
import co.daily.core.dailydemo.VideoTrackType
import co.daily.model.Participant
import co.daily.model.ParticipantId

data class RemoteVideoChooserManual(
    private val participantId: ParticipantId,
    private val trackType: VideoTrackType
) : RemoteVideoChooser {

    override fun chooseRemoteVideo(
        allParticipants: Map<ParticipantId, Participant>,
        displayedRemoteParticipant: ParticipantId?,
        activeSpeaker: ParticipantId?
    ): RemoteVideoChooser.Choice {

        val participant = allParticipants[participantId]

        val videoInfo = participant?.media?.run {
            when (trackType) {
                VideoTrackType.Camera -> camera
                VideoTrackType.ScreenShare -> screenVideo
            }
        }

        @Suppress("FoldInitializerAndIfToElvis")
        if (participant == null || !Utils.isMediaAvailable(videoInfo)) {
            // Revert to auto track selection
            return RemoteVideoChooserAuto.chooseRemoteVideo(
                allParticipants,
                displayedRemoteParticipant,
                activeSpeaker
            )
        }

        val track = participant.media?.run {
            when (trackType) {
                VideoTrackType.Camera -> camera.track
                VideoTrackType.ScreenShare -> screenVideo.track
            }
        }

        return RemoteVideoChooser.Choice(participant, track, trackType)
    }
}
