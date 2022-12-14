package co.daily.core.dailydemo.remotevideochooser

import co.daily.model.Participant
import co.daily.model.ParticipantId

object RemoteVideoChooserHide : RemoteVideoChooser {

    override fun chooseRemoteVideo(
        allParticipants: Map<ParticipantId, Participant>,
        displayedRemoteParticipant: ParticipantId?,
        activeSpeaker: ParticipantId?
    ): RemoteVideoChooser.Choice = RemoteVideoChooser.Choice(null, null, null)
}
