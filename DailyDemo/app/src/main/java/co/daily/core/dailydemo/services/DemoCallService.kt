package co.daily.core.dailydemo.services

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.core.dailydemo.DemoState
import co.daily.core.dailydemo.DemoStateListener
import co.daily.core.dailydemo.DeveloperOptionsDialog
import co.daily.core.dailydemo.VideoTrackType
import co.daily.core.dailydemo.chat.ChatProtocol
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooser
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserAuto
import co.daily.model.AvailableDevices
import co.daily.model.CallState
import co.daily.model.MediaDeviceInfo
import co.daily.model.MeetingToken
import co.daily.model.NetworkStats
import co.daily.model.Participant
import co.daily.model.ParticipantCounts
import co.daily.model.ParticipantId
import co.daily.model.ParticipantLeftReason
import co.daily.model.Recipient
import co.daily.model.RequestListener
import co.daily.model.livestream.LiveStreamStatus
import co.daily.model.recording.RecordingStatus
import co.daily.model.streaming.StreamId
import co.daily.model.transcription.TranscriptionMessageData
import co.daily.model.transcription.TranscriptionStatus
import co.daily.settings.BitRate
import co.daily.settings.CameraInputSettingsUpdate
import co.daily.settings.CameraPublishingSettingsUpdate
import co.daily.settings.ClientSettingsUpdate
import co.daily.settings.FacingModeUpdate
import co.daily.settings.FrameRate
import co.daily.settings.InputSettings
import co.daily.settings.InputSettingsUpdate
import co.daily.settings.PublishingSettings
import co.daily.settings.PublishingSettingsUpdate
import co.daily.settings.Scale
import co.daily.settings.VideoEncodingSettingsUpdate
import co.daily.settings.VideoEncodingsSettingsUpdate
import co.daily.settings.VideoMaxQualityUpdate
import co.daily.settings.VideoMediaTrackSettingsUpdate
import co.daily.settings.VideoSendSettingsUpdate
import co.daily.settings.subscription.MediaSubscriptionSettingsUpdate
import co.daily.settings.subscription.Subscribed
import co.daily.settings.subscription.SubscriptionProfile
import co.daily.settings.subscription.SubscriptionProfileSettings
import co.daily.settings.subscription.SubscriptionProfileSettingsUpdate
import co.daily.settings.subscription.SubscriptionSettings
import co.daily.settings.subscription.SubscriptionSettingsUpdate
import co.daily.settings.subscription.Unsubscribed
import co.daily.settings.subscription.VideoReceiveSettingsUpdate
import co.daily.settings.subscription.VideoSubscriptionSettingsUpdate
import co.daily.settings.subscription.base
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "CallService"

private const val ACTION_LEAVE = "action_leave"

class DemoCallService : Service(), ChatProtocol.ChatProtocolListener {

    private val profileActiveCamera = SubscriptionProfile("activeCamera")
    private val profileActiveScreenShare = SubscriptionProfile("activeScreenShare")

    private val listeners = CopyOnWriteArrayList<DemoStateListener>()

    private var state: DemoState = DemoState.default()

    private var cameraDirection = FacingModeUpdate.user

    private var callClient: CallClient? = null

    private val chatProtocol = ChatProtocol(
        this,
        object : ChatProtocol.ChatProtocolHelper {
            override fun sendAppMessage(msg: ChatProtocol.AppMessage, recipient: Recipient) {
                callClient?.sendAppMessage(Json.encodeToString(msg), recipient)
            }

            override fun participants() = callClient?.participants()
        }
    )

    inner class Binder : android.os.Binder() {

        val chatMessages
            get() = chatProtocol.messages

        fun addListener(listener: DemoStateListener) {
            listeners.add(listener)
            listener.onStateChanged(state)
        }

        fun removeListener(listener: DemoStateListener) {
            listeners.remove(listener)
        }

        fun join(url: String, token: MeetingToken?) {
            updateServiceState {
                it.with(
                    newRemoteVideoChooser = RemoteVideoChooserAuto
                )
            }
            callClient?.join(url, token, createClientSettings()) {
                it.error?.apply {
                    Log.e(TAG, "Got error while joining call: $msg")
                    listeners.forEach { it.onError("Failed to join call: $msg") }
                }
                it.success?.apply {
                    Log.i(TAG, "Successfully joined call")
                }
            }
        }

        fun leave(listener: RequestListener) {
            callClient?.leave(listener)
        }

        fun setUsername(username: String) {
            callClient?.setUserName(username)
        }

        fun flipCameraDirection(listener: RequestListener) {

            cameraDirection = when (cameraDirection) {
                FacingModeUpdate.user -> FacingModeUpdate.environment
                FacingModeUpdate.environment -> FacingModeUpdate.user
            }

            callClient?.updateInputs(
                InputSettingsUpdate(
                    camera = CameraInputSettingsUpdate(
                        settings = VideoMediaTrackSettingsUpdate(
                            facingMode = cameraDirection
                        )
                    )
                ),
                listener
            )
        }

        fun toggleMicInput(enabled: Boolean, listener: RequestListener) {
            Log.d(TAG, "toggleMicInput $enabled")
            callClient?.setInputsEnabled(microphone = enabled, listener = listener)
        }

        fun toggleCamInput(enabled: Boolean, listener: RequestListener) {
            Log.d(TAG, "toggleCamInput $enabled")
            callClient?.setInputsEnabled(camera = enabled, listener = listener)
        }

        fun toggleMicPublishing(enabled: Boolean, listener: RequestListener) {
            Log.d(TAG, "toggleMicPublishing $enabled")
            callClient?.setIsPublishing(microphone = enabled, listener = listener)
        }

        fun toggleCamPublishing(enabled: Boolean, listener: RequestListener) {
            Log.d(TAG, "toggleCamPublishing $enabled")
            callClient?.setIsPublishing(camera = enabled, listener = listener)
        }

        fun setRemoteVideoChooser(remoteVideoChooser: RemoteVideoChooser) {
            Log.i(TAG, "Setting remote video chooser to $remoteVideoChooser")
            updateServiceState { it.with(newRemoteVideoChooser = remoteVideoChooser) }
            updateRemoteVideoChoice()
        }

        fun setAudioDevice(device: MediaDeviceInfo) {
            Log.i(TAG, "Setting audio device to $device")
            if (device.deviceId != state.activeAudioDevice) {
                callClient?.setAudioDevice(device.deviceId)
                updateServiceState { it.with(newActiveAudioDevice = device.deviceId) }
            }
        }

        fun showDeveloperOptionsDialog(activity: Activity) {
            DeveloperOptionsDialog.show(activity, callClient!!)
        }

        fun sendChatMessage(msg: String) {
            chatProtocol.sendMessage(msg)
        }

        fun startScreenShare(mediaProjectionPermissionResultData: Intent) {
            callClient?.startScreenShare(mediaProjectionPermissionResultData)
        }

        fun stopScreenShare() {
            callClient?.stopScreenShare()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand(${intent?.action})")
        if (intent?.action == ACTION_LEAVE) {
            callClient?.leave()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): Binder {
        Log.i(TAG, "onBind")
        return Binder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        stopSelf()
        return false
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        try {
            callClient = CallClient(appContext = applicationContext).apply {
                addListener(callClientListener)
                setupParticipantSubscriptionProfiles(this)

                setInputsEnabled(camera = true, microphone = true)

                updateServiceState { it.with(newAllParticipants = participants().all) }
                state.allParticipants.values.firstOrNull { it.info.isLocal }?.apply {
                    updateLocalVideo(this)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Got exception while creating CallClient", e)
            listeners.forEach { it.onError("Failed to initialize call client") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        callClient?.release()
        callClient = null
    }

    companion object {
        fun leaveIntent(context: Context): Intent =
            Intent(context, DemoCallService::class.java).apply {
                action = ACTION_LEAVE
            }
    }

    private val callClientListener = object : CallClientListener {
        override fun onCallStateUpdated(state: CallState) {
            Log.i(TAG, "onCallStateUpdated($state)")

            updateServiceState { it.with(newStatus = state) }

            chatProtocol.onCallStateUpdated(state)

            if (state == CallState.joined) {
                updateRemoteVideoChoice()
            }
        }

        override fun onInputsUpdated(inputSettings: InputSettings) {
            Log.i(TAG, "onInputsUpdated($inputSettings)")
            updateServiceState {
                it.with(
                    newInputs = DemoState.StreamsState(
                        cameraEnabled = inputSettings.camera.isEnabled,
                        micEnabled = inputSettings.microphone.isEnabled,
                        screenVideoEnabled = inputSettings.screenVideo.isEnabled
                    )
                )
            }
        }

        override fun onPublishingUpdated(publishingSettings: PublishingSettings) {
            Log.i(TAG, "onPublishingUpdated($publishingSettings)")
            updateServiceState {
                it.with(
                    newPublishing = DemoState.StreamsState(
                        cameraEnabled = publishingSettings.camera.isPublishing,
                        micEnabled = publishingSettings.microphone.isPublishing,
                        // Note: due to current SDK limitations, the publish setting
                        // for screenVideo has no effect.
                        screenVideoEnabled = true
                    )
                )
            }
        }

        override fun onParticipantLeft(
            participant: Participant,
            reason: ParticipantLeftReason
        ) {
            Log.i(TAG, "onParticipantLeft($participant, $reason)")
            onParticipantsChanged()
            updateVideoForParticipant(participant)
        }

        override fun onParticipantJoined(participant: Participant) {
            Log.i(TAG, "onParticipantJoined($participant)")
            onParticipantsChanged()
            updateVideoForParticipant(participant)
        }

        override fun onParticipantUpdated(participant: Participant) {
            Log.i(TAG, "onParticipantUpdated($participant)")
            onParticipantsChanged()
            updateVideoForParticipant(participant)
        }

        override fun onActiveSpeakerChanged(activeSpeaker: Participant?) {
            Log.i(TAG, "onActiveSpeakerChanged($activeSpeaker)")
            updateRemoteVideoChoice()
        }

        override fun onError(message: String) {
            Log.i(TAG, "onError($message)")
            listeners.forEach { it.onError(message) }
        }

        override fun onSubscriptionsUpdated(
            subscriptions: Map<ParticipantId, SubscriptionSettings>
        ) {
            Log.i(TAG, "onSubscriptionsUpdated($subscriptions)")
        }

        override fun onSubscriptionProfilesUpdated(
            subscriptionProfiles: Map<SubscriptionProfile, SubscriptionProfileSettings>
        ) {
            Log.i(TAG, "onSubscriptionProfilesUpdated($subscriptionProfiles)")
        }

        override fun onAvailableDevicesUpdated(availableDevices: AvailableDevices) {
            Log.i(TAG, "onAvailableDevicesUpdated($availableDevices)")
            updateServiceState {
                it.with(
                    newAvailableDevices = availableDevices,
                    newActiveAudioDevice = callClient?.audioDevice()
                )
            }
        }

        override fun onAppMessage(message: String, from: ParticipantId) {
            Log.i(TAG, "onAppMessage($message, $from)")
            chatProtocol.onAppMessageReceived(message, from)
        }

        override fun onParticipantCountsUpdated(newParticipantCounts: ParticipantCounts) {
            Log.i(TAG, "onParticipantCountsUpdated($newParticipantCounts)")
        }

        override fun onNetworkStatsUpdated(newNetworkStatistics: NetworkStats) {
            Log.i(TAG, "onNetworkStatsUpdated($newNetworkStatistics)")
        }

        override fun onRecordingStarted(status: RecordingStatus) {
            Log.i(TAG, "onRecordingStarted($status)")
        }

        override fun onRecordingStopped(streamId: StreamId) {
            Log.i(TAG, "onRecordingStopped($streamId)")
        }

        override fun onRecordingError(streamId: StreamId, message: String) {
            Log.i(TAG, "onRecordingError($streamId, $message)")
            listeners.forEach { it.onError("Recording error: $message") }
        }

        override fun onLiveStreamStarted(status: LiveStreamStatus) {
            Log.i(TAG, "onLiveStreamStarted($status)")
        }

        override fun onLiveStreamStopped(streamId: StreamId) {
            Log.i(TAG, "onLiveStreamStopped($streamId)")
        }

        override fun onLiveStreamError(streamId: StreamId, message: String) {
            Log.i(TAG, "onLiveStreamError($streamId, $message)")
            listeners.forEach { it.onError("Live stream error: $message") }
        }

        override fun onLiveStreamWarning(streamId: StreamId, message: String) {
            Log.i(TAG, "onLiveStreamWarning($streamId, $message)")
            listeners.forEach { it.onError("Live stream warning: $message") }
        }

        override fun onTranscriptionStarted(status: TranscriptionStatus) {
            Log.i(TAG, "onTranscriptionStarted($status)")
        }

        override fun onTranscriptionStopped(updatedBy: ParticipantId?, stoppedByError: Boolean) {
            Log.i(TAG, "onTranscriptionStopped($updatedBy, $stoppedByError)")
        }

        override fun onTranscriptionMessage(data: TranscriptionMessageData) {
            Log.i(TAG, "onTranscriptionMessage($data)")
        }

        override fun onTranscriptionError(message: String) {
            Log.i(TAG, "onTranscriptionError($message)")
        }
    }

    private fun onParticipantsChanged() {
        updateServiceState { it.with(newAllParticipants = callClient?.participants()?.all ?: emptyMap()) }
    }

    private fun updateVideoForParticipant(participant: Participant) {
        if (participant.info.isLocal) {
            updateLocalVideo(participant)
        } else {
            updateRemoteVideoChoice()
        }
    }

    private fun updateServiceState(stateUpdate: (DemoState) -> DemoState) {
        val newState = stateUpdate(state)
        state = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    private fun setupParticipantSubscriptionProfiles(
        callClient: CallClient
    ) {
        callClient.updateSubscriptionProfiles(
            mapOf(
                profileActiveCamera to
                    SubscriptionProfileSettingsUpdate(
                        camera = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Subscribed(),
                            receiveSettings = VideoReceiveSettingsUpdate(
                                maxQuality = VideoMaxQualityUpdate.high
                            )
                        ),
                        screenVideo = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Unsubscribed()
                        )
                    ),
                profileActiveScreenShare to
                    SubscriptionProfileSettingsUpdate(
                        camera = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Unsubscribed()
                        ),
                        screenVideo = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Subscribed(),
                            receiveSettings = VideoReceiveSettingsUpdate(
                                maxQuality = VideoMaxQualityUpdate.high
                            )
                        )
                    ),
                SubscriptionProfile.base to
                    SubscriptionProfileSettingsUpdate(
                        camera = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Unsubscribed()
                        ),
                        screenVideo = VideoSubscriptionSettingsUpdate(
                            subscriptionState = Unsubscribed()
                        )
                    )
            )
        )
    }

    private fun updateLocalVideo(participant: Participant) {
        updateServiceState { it.with(newLocalParticipantTrack = participant.media?.camera?.track) }
    }

    private fun updateRemoteVideoChoice() {
        callClient?.let { callClient ->
            val displayedRemoteParticipant = state.displayedRemoteParticipant?.participant?.id
            val activeSpeaker = callClient.activeSpeaker()?.takeUnless { it.info.isLocal }?.id

            val choice = state.remoteVideoChooser.chooseRemoteVideo(
                state.allParticipants,
                displayedRemoteParticipant,
                activeSpeaker
            )

            if (choice != state.displayedRemoteParticipant) {

                updateServiceState { it.with(newDisplayedRemoteParticipant = choice) }

                callClient.updateSubscriptions(
                    // Subscribe to the currently displayed participant
                    forParticipants = choice.participant?.run {
                        mapOf(
                            id to SubscriptionSettingsUpdate(
                                profile = when (choice.trackType) {
                                    VideoTrackType.Camera -> profileActiveCamera
                                    VideoTrackType.ScreenShare -> profileActiveScreenShare
                                    is VideoTrackType.CustomTrack -> SubscriptionProfile.base
                                    null -> SubscriptionProfile.base
                                },
                                media = when (choice.trackType) {
                                    is VideoTrackType.CustomTrack -> MediaSubscriptionSettingsUpdate(
                                        customVideo = mapOf(
                                            choice.trackType.name to VideoSubscriptionSettingsUpdate(
                                                subscriptionState = Subscribed()
                                            )
                                        )
                                    )
                                    else -> null
                                },
                            )
                        )
                    } ?: mapOf(),
                    // Unsubscribe from remote participants not currently displayed
                    forParticipantsWithProfiles = mapOf(
                        profileActiveCamera to SubscriptionSettingsUpdate(
                            profile = SubscriptionProfile.base,
                        ),
                        profileActiveScreenShare to SubscriptionSettingsUpdate(
                            profile = SubscriptionProfile.base,
                        )
                    )
                )
            }
        }
    }

    private fun createClientSettings(): ClientSettingsUpdate {
        return ClientSettingsUpdate(
            publishingSettings = PublishingSettingsUpdate(
                camera = CameraPublishingSettingsUpdate(
                    sendSettings = VideoSendSettingsUpdate(
                        encodings = VideoEncodingsSettingsUpdate(
                            settings = mapOf(
                                VideoMaxQualityUpdate.low to
                                    VideoEncodingSettingsUpdate(
                                        maxBitrate = BitRate(80000),
                                        maxFramerate = FrameRate(10),
                                        scaleResolutionDownBy = Scale(4F)
                                    ),
                                VideoMaxQualityUpdate.medium to
                                    VideoEncodingSettingsUpdate(
                                        maxBitrate = BitRate(680000),
                                        maxFramerate = FrameRate(30),
                                        scaleResolutionDownBy = Scale(1F)
                                    )
                            )
                        )
                    )
                )
            )
        )
    }

    override fun onMessageListUpdated() {
        listeners.forEach { it.onChatMessageListUpdated() }
    }

    override fun onRemoteMessageReceived(chatMessage: ChatProtocol.ChatMessage) {
        listeners.forEach { it.onChatRemoteMessageReceived(chatMessage) }
    }
}
