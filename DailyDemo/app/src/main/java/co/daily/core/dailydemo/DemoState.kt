package co.daily.core.dailydemo

import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooser
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserAuto
import co.daily.model.AvailableDevices
import co.daily.model.CallState
import co.daily.model.MediaStreamTrack
import co.daily.model.Participant
import co.daily.model.ParticipantId
import co.daily.settings.VideoProcessor

// As an optimization for larger calls, it would be possible to modify this to
// represent state updates rather than entire state snapshots. The MainActivity
// could then respond to only the parts of the state which have changed.

data class DemoState(
    val status: CallState,
    val inputs: StreamsState,
    val publishing: StreamsState,
    val localParticipantTrack: MediaStreamTrack?,
    val displayedRemoteParticipant: RemoteVideoChooser.Choice?,
    val remoteVideoChooser: RemoteVideoChooser,
    val allParticipants: Map<ParticipantId, Participant>,
    val availableDevices: AvailableDevices,
    val activeAudioDevice: String?,
    val screenShareActive: Boolean,
    val videoProcessor: VideoProcessor?,
    val customAudioTrackFreqHz: Int?
) {
    data class StreamsState(
        val cameraEnabled: Boolean,
        val micEnabled: Boolean,
        val screenVideoEnabled: Boolean
    )

    fun with(
        newStatus: CallState = status,
        newInputs: StreamsState = inputs,
        newPublishing: StreamsState = publishing,
        newLocalParticipantTrack: MediaStreamTrack? = localParticipantTrack,
        newDisplayedRemoteParticipant: RemoteVideoChooser.Choice? = displayedRemoteParticipant,
        newRemoteVideoChooser: RemoteVideoChooser = remoteVideoChooser,
        newAllParticipants: Map<ParticipantId, Participant> = allParticipants,
        newAvailableDevices: AvailableDevices = availableDevices,
        newActiveAudioDevice: String? = activeAudioDevice,
        newScreenShareActive: Boolean = screenShareActive,
        newVideoProcessor: VideoProcessor? = videoProcessor,
        newCustomAudioTrackFreqHz: Int? = customAudioTrackFreqHz,
    ) = DemoState(
        newStatus,
        newInputs,
        newPublishing,
        newLocalParticipantTrack,
        newDisplayedRemoteParticipant,
        newRemoteVideoChooser,
        newAllParticipants,
        newAvailableDevices,
        newActiveAudioDevice,
        newScreenShareActive,
        newVideoProcessor,
        newCustomAudioTrackFreqHz
    )

    companion object {
        fun default(): DemoState = DemoState(
            status = CallState.initialized,
            inputs = StreamsState(cameraEnabled = true, micEnabled = true, screenVideoEnabled = false),
            publishing = StreamsState(cameraEnabled = true, micEnabled = true, screenVideoEnabled = true),
            localParticipantTrack = null,
            displayedRemoteParticipant = null,
            remoteVideoChooser = RemoteVideoChooserAuto,
            allParticipants = emptyMap(),
            availableDevices = AvailableDevices(
                camera = emptyList(),
                microphone = emptyList(),
                speaker = emptyList(),
                audio = emptyList()
            ),
            activeAudioDevice = null,
            screenShareActive = false,
            videoProcessor = null,
            customAudioTrackFreqHz = null
        )
    }
}
