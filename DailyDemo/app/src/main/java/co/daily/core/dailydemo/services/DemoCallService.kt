package co.daily.core.dailydemo.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.core.dailydemo.DemoState
import co.daily.core.dailydemo.DemoStateListener
import co.daily.core.dailydemo.VideoTrackType
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooser
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserAuto
import co.daily.model.AvailableDevices
import co.daily.model.CallState
import co.daily.model.MediaDeviceInfo
import co.daily.model.Participant
import co.daily.model.ParticipantId
import co.daily.model.ParticipantLeftReason
import co.daily.settings.BitRate
import co.daily.settings.CameraPublishingSettingsUpdate
import co.daily.settings.ClientSettingsUpdate
import co.daily.settings.Disable
import co.daily.settings.Enable
import co.daily.settings.FrameRate
import co.daily.settings.InputSettings
import co.daily.settings.InputSettingsUpdate
import co.daily.settings.PublishingSettings
import co.daily.settings.PublishingSettingsUpdate
import co.daily.settings.Scale
import co.daily.settings.VideoEncodingSettingsUpdate
import co.daily.settings.VideoEncodingsSettingsUpdate
import co.daily.settings.VideoMaxQualityUpdate
import co.daily.settings.VideoSendSettingsUpdate
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
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "CallService"

private const val ACTION_LEAVE = "action_leave"

class DemoCallService : Service() {

    private val profileActiveCamera = SubscriptionProfile("activeCamera")
    private val profileActiveScreenShare = SubscriptionProfile("activeScreenShare")

    private val listeners = CopyOnWriteArrayList<DemoStateListener>()

    private var state: DemoState = DemoState.default()

    inner class Binder : android.os.Binder() {

        fun addListener(listener: DemoStateListener) {
            listeners.add(listener)
            listener.onStateChanged(state)
        }

        fun removeListener(listener: DemoStateListener) {
            listeners.remove(listener)
        }

        fun join(url: String) {
            updateServiceState {
                it.with(
                    newRemoteVideoChooser = RemoteVideoChooserAuto
                )
            }
            callClient?.join(url, null, createClientSettings()) {
                it.error?.apply {
                    Log.e(TAG, "Got error while joining call: $msg")
                    listeners.forEach { it.onError("Failed to join call: $msg") }
                }
                it.success?.apply {
                    Log.i(TAG, "Successfully joined call in ${meetingSession.topology} mode")
                }
            }
        }

        fun leave() {
            callClient?.leave()
        }

        fun setUsername(username: String) {
            callClient?.setUserName(username)
        }

        fun toggleMicInput(enabled: Boolean) {
            Log.d(TAG, "toggleMicInput $enabled")
            callClient?.updateInputs(
                InputSettingsUpdate(
                    microphone = if (enabled) Enable() else Disable()
                )
            )
        }

        fun toggleCamInput(enabled: Boolean) {
            Log.d(TAG, "toggleCamInput $enabled")
            callClient?.updateInputs(
                InputSettingsUpdate(
                    camera = if (enabled) Enable() else Disable()
                )
            )
        }

        fun toggleMicPublishing(enabled: Boolean) {
            Log.d(TAG, "toggleMicPublishing $enabled")
            callClient?.updatePublishing(
                PublishingSettingsUpdate(
                    microphone = if (enabled) Enable() else Disable()
                )
            )
        }

        fun toggleCamPublishing(enabled: Boolean) {
            Log.d(TAG, "toggleCamPublishing $enabled")
            callClient?.updatePublishing(
                PublishingSettingsUpdate(
                    camera = if (enabled) Enable() else Disable()
                )
            )
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

                updateInputs(
                    inputSettings = InputSettingsUpdate(
                        microphone = Enable(),
                        camera = Enable()
                    )
                )

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

    private var callClient: CallClient? = null

    private val callClientListener = object : CallClientListener {
        override fun onCallStateUpdated(state: CallState) {
            Log.i(TAG, "onCallStateUpdated($state)")
            updateServiceState { it.with(newStatus = state) }
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
                        micEnabled = inputSettings.microphone.isEnabled
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
                        micEnabled = publishingSettings.microphone.isPublishing
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

    private fun setupParticipantSubscriptionProfiles(callClient: CallClient) {
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
        callClient?.apply {
            val displayedRemoteParticipant = state.displayedRemoteParticipant?.participant?.id
            val activeSpeaker = activeSpeaker()?.takeUnless { it.info.isLocal }?.id

            val choice = state.remoteVideoChooser.chooseRemoteVideo(
                state.allParticipants,
                displayedRemoteParticipant,
                activeSpeaker
            )

            if (choice != state.displayedRemoteParticipant) {

                updateServiceState { it.with(newDisplayedRemoteParticipant = choice) }

                updateSubscriptions(
                    // Subscribe to the currently displayed participant
                    forParticipants = choice.participant?.run {
                        mapOf(
                            id to SubscriptionSettingsUpdate(
                                profile = when (choice.trackType) {
                                    VideoTrackType.Camera -> profileActiveCamera
                                    VideoTrackType.ScreenShare -> profileActiveScreenShare
                                    null -> SubscriptionProfile.base
                                }
                            )
                        )
                    } ?: mapOf(),
                    // Unsubscribe from remote participants not currently displayed
                    forParticipantsWithProfiles = mapOf(
                        profileActiveCamera to SubscriptionSettingsUpdate(
                            profile = SubscriptionProfile.base
                        ),
                        profileActiveScreenShare to SubscriptionSettingsUpdate(
                            profile = SubscriptionProfile.base
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
}
