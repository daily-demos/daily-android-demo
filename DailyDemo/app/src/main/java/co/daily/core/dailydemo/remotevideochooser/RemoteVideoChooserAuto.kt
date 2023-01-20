package co.daily.core.dailydemo.remotevideochooser

import co.daily.core.dailydemo.Utils
import co.daily.core.dailydemo.VideoTrackType
import co.daily.model.MediaStreamTrack
import co.daily.model.Participant
import co.daily.model.ParticipantId

object RemoteVideoChooserAuto : RemoteVideoChooser {

    override fun chooseRemoteVideo(
        allParticipants: Map<ParticipantId, Participant>,
        displayedRemoteParticipant: ParticipantId?,
        activeSpeaker: ParticipantId?
    ): RemoteVideoChooser.Choice {

        val participant: Participant?
        val track: MediaStreamTrack?
        val trackType: VideoTrackType?

        val participantWhoIsSharingScreen =
            allParticipants.values.firstOrNull { Utils.isMediaAvailable(it.media?.screenVideo) }?.id

        /*
            The preference is:
                - The participant who is sharing their screen
                - The active speaker
                - The last displayed remote participant
                - Any remote participant who has their video opened
        */
        val participantId = participantWhoIsSharingScreen
            ?: activeSpeaker
            ?: displayedRemoteParticipant
            ?: allParticipants.values.firstOrNull {
                !it.info.isLocal && Utils.isMediaAvailable(it.media?.camera)
            }?.id

        participant = allParticipants[participantId]

        if (Utils.isMediaAvailable(participant?.media?.screenVideo)) {
            track = participant?.media?.screenVideo?.track
            trackType = VideoTrackType.ScreenShare
        } else if (Utils.isMediaAvailable(participant?.media?.camera)) {
            track = participant?.media?.camera?.track
            trackType = VideoTrackType.Camera
        } else {
            track = null
            trackType = null
        }

        return RemoteVideoChooser.Choice(participant, track, trackType)
    }
}
