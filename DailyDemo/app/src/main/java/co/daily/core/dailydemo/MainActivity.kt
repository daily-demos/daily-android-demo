package co.daily.core.dailydemo

import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.exception.UnknownCallClientError
import co.daily.model.CallState
import co.daily.model.Participant
import co.daily.model.ParticipantId
import co.daily.settings.*
import co.daily.settings.subscription.*
import kotlinx.coroutines.launch
import co.daily.view.VideoView

private const val TAG = "daily_demo_app"

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            if (result.values.any { !it }) {
                checkPermissions()
            } else {
              // permission is granted, we can initialize
              initialize()
            }
        }

    private lateinit var addurl: EditText
    private lateinit var callClient: CallClient
    private var localContainer: FrameLayout? = null
    private var remoteContainer: FrameLayout? = null
    private var localVideoView: VideoView? = null
    private var remoteVideoView: VideoView? = null

    private lateinit var joinButton: Button
    private lateinit var leaveButton: Button
    private lateinit var micInputButton: ImageButton
    private lateinit var camInputButton: ImageButton
    private lateinit var micPublishButton: ImageButton
    private lateinit var camPublishButton: ImageButton

    private lateinit var localCameraMaskView: TextView
    private lateinit var remoteCameraMaskView: TextView
    private var displayedRemoteParticipant: Participant? = null

    // The default profiles that we are going to use
    val <SubscriptionProfile> SubscriptionProfile.activeRemote get() = SubscriptionProfile("activeRemote")

    private var callClientListener = object : CallClientListener {

        override fun onError(message: String) {
            Log.d(TAG, "Received error ${message}")
        }

        override fun onCallStateUpdated(
            state: CallState
        ) {
            when(state) {
                CallState.joining -> {}
                CallState.joined -> {
                    onJoinedMeeting()
                }
                CallState.leaving -> {}
                CallState.left -> {
                    resetAppState()
                }
                CallState.new -> {
                    Log.d(TAG, "CallState.new")
                }
            }
        }

        override fun onInputsUpdated(inputSettings: InputSettings) {
            Log.d(TAG, "On Inputs updated ${InputSettings}")
            refreshInputPublishButtonsState()

            val cameraEnabled = callClient.inputs().camera.isEnabled
            localCameraMaskView.visibility = if (cameraEnabled) View.GONE else View.VISIBLE
            localVideoView?.visibility = if (cameraEnabled) View.VISIBLE else View.GONE
        }

        override fun onPublishingUpdated(publishingSettings: PublishingSettings) {
            refreshInputPublishButtonsState()
        }

        override fun onParticipantJoined(participant: Participant) {
            Log.d(TAG, "onParticipantJoined local ${participant.info.isLocal}")
            updateParticipantVideoView(participant)
        }

        override fun onSubscriptionsUpdated(subscriptions: Map<ParticipantId, SubscriptionSettings>) {
            Log.d(TAG, "onSubscriptionsUpdated $subscriptions")
        }

        override fun onSubscriptionProfilesUpdated(subscriptionProfiles: Map<SubscriptionProfile, SubscriptionProfileSettings>) {
            Log.d(TAG, "onSubscriptionProfilesUpdated $subscriptionProfiles")
        }

        override fun onParticipantUpdated(participant: Participant) {
            Log.d(TAG, "onParticipantUpdated local ${participant.info.isLocal}")
            updateParticipantVideoView(participant)
        }

        override fun onActiveSpeakerChanged(activeSpeaker: Participant?) {
            Log.d(TAG, "onActiveSpeakerChanged ${activeSpeaker?.info?.userName}")
            renderPreferredRemoteParticipant()
        }

        override fun onParticipantLeft(participant: Participant) {
            Log.d(TAG, "onParticipantLeft")
            if(participant.id == displayedRemoteParticipant?.id){
                displayedRemoteParticipant = null
                renderPreferredRemoteParticipant()
            }
        }
    }

    private fun onJoinedMeeting() {
        joinButton.visibility = View.GONE
        leaveButton.visibility = View.VISIBLE
        leaveButton.isEnabled = true
        setupParticipantSubscriptionProfiles()
        renderPreferredRemoteParticipant()
    }

    private fun setupParticipantSubscriptionProfiles() {
        lifecycleScope.launch() {
            val subscriptionProfilesResult = callClient.updateSubscriptionProfiles(mapOf(
                SubscriptionProfile.activeRemote to
                        SubscriptionProfileSettingsUpdate(
                            camera = VideoSubscriptionSettingsUpdate(
                                receiveSettings = VideoReceiveSettingsUpdate(
                                    maxQuality = VideoMaxQualityUpdate.high
                                )
                            )
                        ),
                // By default, all the participants that join this meeting, will have the video quality to low
                SubscriptionProfile.base to
                        SubscriptionProfileSettingsUpdate(
                            camera = VideoSubscriptionSettingsUpdate(
                                receiveSettings = VideoReceiveSettingsUpdate(
                                    maxQuality = VideoMaxQualityUpdate.low
                                )
                            )
                        )
            ))
            Log.d(TAG, "subscription profiles result $subscriptionProfilesResult")
        }
    }

    private fun resetAppState() {
        addurl.isEnabled = true
        joinButton.visibility = View.VISIBLE
        joinButton.isEnabled = true
        leaveButton.isEnabled = false
        leaveButton.visibility = View.GONE
        clearLocalVideoView()
        clearRemoteVideoView()
        remoteCameraMaskView.text = resources.getText(R.string.join_meeting_instructions)
    }

    private fun renderPreferredRemoteParticipant(){
        Log.d(TAG, "renderPreferredRemoteParticipant")

        val allParticipants = callClient.participants().all.values
        val participantWhoIsSharingScreen = allParticipants.firstOrNull{ it.media?.screenVideo?.track != null }
        var activeSpeaker = if (callClient.activeSpeaker()?.info?.isLocal == false) callClient.activeSpeaker() else null
        Log.d(TAG, "activeSpeaker: ${activeSpeaker?.info?.userName}")
        // Updating the displayed remote participant with the most recent info, otherwise we can get a state where or cache is different from his current state
        displayedRemoteParticipant = allParticipants.firstOrNull { it.id == displayedRemoteParticipant?.id }

        /*
        The preference is:
            - The participant who is sharing his screen
            - The active speaker
            - The last displayed remote participant
            - Any remote participant who has his video opened
        */
        val nextParticipant = participantWhoIsSharingScreen
            ?: activeSpeaker
            ?: displayedRemoteParticipant
            ?: callClient.participants().all.values.firstOrNull{ !it.info.isLocal && it.media?.camera?.track != null }

        // Render the next particpant in the list if any
        updateRemoteParticipantVideoView(nextParticipant)
        if(nextParticipant != null){
            changePreferredRemoteParticipantSubscription(activeParticipant = nextParticipant)
        }
    }

    private fun changePreferredRemoteParticipantSubscription(activeParticipant: Participant) {
        lifecycleScope.launch() {
            val subscriptionsResult = callClient.updateSubscriptions(
                // Improve the video quality of the remote participant that is currently displayed
                forParticipants = mapOf(
                    activeParticipant.id to SubscriptionSettingsUpdate(
                        profile = SubscriptionProfile.activeRemote
                    )
                ),
                // Reduce video quality of remote participants not currently displayed
                forParticipantsWithProfiles = mapOf(
                    SubscriptionProfile.activeRemote to SubscriptionSettingsUpdate(
                        profile = SubscriptionProfile.base
                    )
                )
            )
            Log.d(TAG, "Update subscriptions result $subscriptionsResult")
        }
    }

    private fun clearRemoteVideoView() {
        remoteVideoView?.release()
        remoteContainer?.removeView(remoteVideoView)
        remoteVideoView = null
        remoteCameraMaskView.visibility = View.VISIBLE
        displayedRemoteParticipant = null
    }

    private fun clearLocalVideoView() {
        localVideoView?.release()
        localContainer?.removeView(localVideoView)
        localVideoView = null
        localCameraMaskView.visibility = View.VISIBLE
    }

    private fun updateRemoteCameraMaskViewMessage() {
        val message =
            if (displayedRemoteParticipant != null )
                displayedRemoteParticipant?.info?.userName?: "Guest"
            else
                when (val amountOfParticipants = callClient.participants().all.size) {
                    1 -> resources.getString(R.string.no_one_else_in_meeting)
                    2 -> callClient.participants().all.filter { !it.value.info.isLocal }.entries.first().value.info.userName
                    else -> resources.getString(R.string.amount_of_participants_at_meeting, amountOfParticipants-1)
                }
        remoteCameraMaskView.text = message
    }

    private fun updateLocalParticipantVideoView(participant: Participant){
        // make sure we are only handling local participants
        if (!participant.info.isLocal) {
            return
        }

        if (localVideoView == null) {
            localVideoView = VideoView(this@MainActivity)
            localContainer?.addView(localVideoView)
            localVideoView?.bringVideoToFront()
        }
        localVideoView?.track = participant.media?.camera?.track
    }

    private fun updateParticipantVideoView(participant: Participant){
        if (participant.info.isLocal) {
            updateLocalParticipantVideoView(participant)
        } else {
            renderPreferredRemoteParticipant()
        }
    }

    private fun updateRemoteParticipantVideoView(participant: Participant? = null) {
        //here we must invoke to adjust the resolution
        displayedRemoteParticipant = participant
        val track = participant?.media?.screenVideo?.track ?: participant?.media?.camera?.track
        if (remoteVideoView == null) {
            remoteVideoView = VideoView(this@MainActivity)
            remoteContainer?.addView(remoteVideoView)
        }
        remoteVideoView?.videoScaleMode = if (participant?.media?.screenVideo?.track != null) VideoView.VideoScaleMode.FIT else VideoView.VideoScaleMode.FILL
        remoteVideoView?.track = track
        val containsTrack = track != null
        remoteCameraMaskView.visibility = if(containsTrack) View.GONE else View.VISIBLE
        remoteVideoView?.visibility = if(containsTrack) View.VISIBLE else View.GONE
        updateRemoteCameraMaskViewMessage()
    }

    override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        joinButton = findViewById(R.id.call_button)
        leaveButton = findViewById(R.id.hangup_button)
        micInputButton = findViewById(R.id.input_mic_button)
        camInputButton = findViewById(R.id.input_camera_button)
        micPublishButton = findViewById(R.id.publish_mic_button)
        camPublishButton = findViewById(R.id.publish_camera_button)

        localContainer = findViewById(R.id.local_video_view_container)
        remoteContainer = findViewById(R.id.remote_video_view_container)

        localCameraMaskView = findViewById(R.id.local_camera_mask_view)
        remoteCameraMaskView = findViewById(R.id.remote_camera_mask_view)
        addurl = findViewById(R.id.aurl)

        checkPermissions()
    }

    private fun initialize() {
        initCallClient()
        initEventListeners()
    }

    private fun toggleCamInput(enabled:Boolean) {
        lifecycleScope.launch() {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    camera = if (enabled) Enable() else Disable()
                )
            )
        }
    }

    private fun toggleMicInput(enabled:Boolean) {
        Log.d(TAG, "toggleMicInput ${enabled}")
        lifecycleScope.launch() {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    microphone = if (enabled) Enable() else Disable()
                )
            )
        }
    }

    private fun toggleMicPublish(enabled:Boolean) {
        Log.d(TAG, "toggleMicPublish ${enabled}")
        lifecycleScope.launch() {
            callClient.updatePublishing(
                publishSettings = PublishingSettingsUpdate(
                    microphone = MicrophonePublishingSettingsUpdate(
                        isPublishing = if (enabled) Enable() else Disable()
                    )
                )
            )
        }
    }

    private fun toggleCamPublish(enabled:Boolean) {
        Log.d(TAG, "toggleMicPublish ${enabled}")
        lifecycleScope.launch() {
            callClient.updatePublishing(
                publishSettings = PublishingSettingsUpdate(
                    camera = CameraPublishingSettingsUpdate(
                        isPublishing = if (enabled) Enable() else Disable()
                    )
                )
            )
        }
    }

    private fun refreshInputPublishButtonsState() {
        micInputButton.backgroundTintList = if (callClient.inputs().microphone.isEnabled)
            resources.getColorStateList(R.color.colorEnabled, null) else
            resources.getColorStateList(R.color.colorDisabled, null)
        camInputButton.backgroundTintList = if (callClient.inputs().camera.isEnabled)
            resources.getColorStateList(R.color.colorEnabled, null) else
            resources.getColorStateList(R.color.colorDisabled, null)
        micPublishButton.backgroundTintList = if (callClient.publishing().microphone.isPublishing)
            resources.getColorStateList(R.color.colorEnabled, null) else
            resources.getColorStateList(R.color.colorDisabled, null)
        camPublishButton.backgroundTintList = if (callClient.publishing().camera.isPublishing)
            resources.getColorStateList(R.color.colorEnabled, null) else
            resources.getColorStateList(R.color.colorDisabled, null)
    }

    private fun createClientSettingsIntent():ClientSettingsUpdate {
        val publishingSettingsIntent = PublishingSettingsUpdate(
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
        return ClientSettingsUpdate(
            publishingSettings = publishingSettingsIntent
        )
    }

    private fun showMessage(message: String) {
        val layout = layoutInflater.inflate(R.layout.custom_toast, findViewById(R.id.custom_toast_id))
        val toastMessage:TextView = layout.findViewById(R.id.custom_toast_message)
        toastMessage.text = message
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
        toast.view = layout
        toast.setGravity(Gravity.TOP, 0, 20)
        toast.show()
    }

    private fun initEventListeners() {
        addurl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val containsUrl = Patterns.WEB_URL.matcher(addurl.text.toString()).matches()
                joinButton.isEnabled = containsUrl
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        val clientSettingsIntent = createClientSettingsIntent()
        joinButton.setOnClickListener {
            // Only enable leave after joined
            joinButton.isEnabled = false
            addurl.isEnabled = false
            lifecycleScope.launch {
                try {
                    val url = addurl.text.toString()
                    callClient.join(url, clientSettingsIntent)
                    Log.d(TAG, "ClientSettings ${clientSettingsIntent}")
                    val participants = callClient.participants()
                    Log.d(TAG, "participants $participants")
                    Log.d(TAG, "me $participants.local")
                    Log.d(TAG, "all ${callClient.participants().all}")

                    Log.d(TAG, "inputs ${callClient.inputs()}")
                    Log.d(TAG, "publishing ${callClient.publishing()}")
                    Log.d(TAG, "call state ${callClient.callState()}")
                } catch (e: UnknownCallClientError) {
                    Log.d(TAG, "Failed to join call")
                    callClient.leave()
                    showMessage("Failed to join call")
                }
            }
        }

        leaveButton.setOnClickListener {
            it.isEnabled = false
            callClient.leave()
        }

        micInputButton.setOnClickListener {
            toggleMicInput(!callClient.inputs().microphone.isEnabled)
        }

        camInputButton.setOnClickListener {
            toggleCamInput(!callClient.inputs().camera.isEnabled)
        }

        micPublishButton.setOnClickListener {
            toggleMicPublish(!callClient.publishing().microphone.isPublishing)
        }

        camPublishButton.setOnClickListener {
            toggleCamPublish(!callClient.publishing().camera.isPublishing)
        }
    }

    private fun initCallClient() {
        callClient = CallClient(applicationContext, this.lifecycle).apply {
            addListener(callClientListener)
        }
        toggleMicInput(true)
        toggleCamInput(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG,"MainActivity on destroy has been invoked!")
    }

    private fun checkPermissions() {
        val permissionList = applicationContext.packageManager.
            getPackageInfo(applicationContext.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions

        val notGrantedPermissions = permissionList.map {
            Pair(it, ContextCompat.checkSelfPermission(applicationContext, it))
        }.filter {
            it.second != PackageManager.PERMISSION_GRANTED
        }.map {
            it.first
        }.toTypedArray()

        if (notGrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(notGrantedPermissions)
        } else {
            // permission is granted, we can initialize
            initialize()
        }
    }
}
