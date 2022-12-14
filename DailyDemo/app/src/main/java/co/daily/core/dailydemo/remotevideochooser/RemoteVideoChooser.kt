package co.daily.core.dailydemo.remotevideochooser

import co.daily.core.dailydemo.VideoTrackType
import co.daily.model.MediaStreamTrack
import co.daily.model.Participant
import co.daily.model.ParticipantId

interface RemoteVideoChooser {

    data class Choice(
        val participant: Participant?,
        val track: MediaStreamTrack?,
        val trackType: VideoTrackType?
    )

    fun chooseRemoteVideo(
        allParticipants: Map<ParticipantId, Participant>,
        displayedRemoteParticipant: ParticipantId?,
        activeSpeaker: ParticipantId?
    ): Choice
}
