package co.daily.core.dailydemo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.exception.UnknownCallClientError
import co.daily.model.AvailableDevices
import co.daily.model.CallState
import co.daily.model.MediaDeviceInfo
import co.daily.model.MediaState
import co.daily.model.MediaStreamTrack
import co.daily.model.Participant
import co.daily.model.ParticipantId
import co.daily.model.ParticipantVideoInfo
import co.daily.settings.BitRate
import co.daily.settings.CameraPublishingSettingsUpdate
import co.daily.settings.ClientSettingsUpdate
import co.daily.settings.Disable
import co.daily.settings.Enable
import co.daily.settings.FrameRate
import co.daily.settings.InputSettings
import co.daily.settings.InputSettingsUpdate
import co.daily.settings.MicrophonePublishingSettingsUpdate
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
import co.daily.view.VideoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

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

    private lateinit var prefs: Preferences

    private lateinit var addurl: EditText
    private lateinit var callClient: CallClient
    private var localContainer: FrameLayout? = null
    private var remoteContainer: FrameLayout? = null
    private lateinit var localVideoView: VideoView
    private lateinit var remoteVideoView: VideoView

    private lateinit var urlBar: View
    private lateinit var bottomToolbars: View
    private lateinit var backgroundTapInterceptor: View

    private lateinit var inCallButtons: View

    private lateinit var joinButton: Button
    private lateinit var leaveButton: ImageButton
    private lateinit var moreOptionsButton: ImageButton

    private lateinit var micInputButton: ToggleButton
    private lateinit var camInputButton: ToggleButton
    private lateinit var micPublishButton: ToggleButton
    private lateinit var camPublishButton: ToggleButton
    private lateinit var audioDevicesSpinner: Spinner

    private lateinit var localVideoToggle: ToggleButton

    private lateinit var localCameraMaskView: TextView
    private lateinit var remoteCameraMaskView: TextView
    private var displayedRemoteParticipant: Participant? = null

    private var remoteVideoChoice: RemoteVideoChoice = RemoteVideoChoice.Auto

    private var buttonHidingEnabled = false
    private val buttonHidingRunnable: Runnable = Runnable {
        bottomToolbars.animate()
            .alpha(0.0f)
            .setDuration(500)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    bottomToolbars.visibility = View.GONE
                }
            })
    }

    // Set this to true to disable automatic show/hide of local video view
    private var userToggledLocalPreview: Boolean = false

    // The default profiles that we are going to use
    private val profileActiveCamera = SubscriptionProfile("activeCamera")
    private val profileActiveScreenShare = SubscriptionProfile("activeScreenShare")

    private var callClientListener = object : CallClientListener {

        override fun onError(message: String) {
            Log.d(TAG, "Received error $message")
        }

        override fun onCallStateUpdated(
            state: CallState
        ) {
            Log.d(TAG, "onCallStateUpdated: $state")
            when (state) {
                CallState.joining -> {}
                CallState.joined -> {
                    onJoinedMeeting()
                }
                CallState.leaving -> {}
                CallState.left -> {
                    resetAppState()
                }
                CallState.new -> {}
            }
        }

        override fun onInputsUpdated(inputSettings: InputSettings) {
            Log.d(TAG, "onInputsUpdated: $inputSettings")
            refreshInputPublishButtonsState()

            val cameraEnabled = callClient.inputs().camera.isEnabled
            localCameraMaskView.visibility = if (cameraEnabled) View.GONE else View.VISIBLE
            localVideoView.visibility = if (cameraEnabled) View.VISIBLE else View.GONE
        }

        override fun onPublishingUpdated(publishingSettings: PublishingSettings) {
            Log.d(TAG, "onPublishingUpdated: $publishingSettings")
            refreshInputPublishButtonsState()
        }

        override fun onParticipantJoined(participant: Participant) {
            Log.d(TAG, "onParticipantJoined: $participant")
            updateParticipantVideoView(participant)
        }

        override fun onSubscriptionsUpdated(subscriptions: Map<ParticipantId, SubscriptionSettings>) {
            Log.d(TAG, "onSubscriptionsUpdated: $subscriptions")
        }

        override fun onSubscriptionProfilesUpdated(subscriptionProfiles: Map<SubscriptionProfile, SubscriptionProfileSettings>) {
            Log.d(TAG, "onSubscriptionProfilesUpdated: $subscriptionProfiles")
        }

        override fun onParticipantUpdated(participant: Participant) {
            Log.d(TAG, "onParticipantUpdated: $participant")
            updateParticipantVideoView(participant)
        }

        override fun onActiveSpeakerChanged(activeSpeaker: Participant?) {
            Log.d(TAG, "onActiveSpeakerChanged: ${activeSpeaker?.info?.userName}")
            choosePreferredRemoteParticipant()
        }

        override fun onParticipantLeft(participant: Participant) {
            Log.d(TAG, "onParticipantLeft + ${participant.id}")

            if (remoteVideoChoice is RemoteVideoChoice.Track &&
                (remoteVideoChoice as RemoteVideoChoice.Track).participantId == participant.id
            ) {
                remoteVideoChoice = RemoteVideoChoice.Auto
            }

            if (participant.id == displayedRemoteParticipant?.id) {
                displayedRemoteParticipant = null
                choosePreferredRemoteParticipant()
            }
        }

        override fun onAvailableDevicesUpdated(availableDevices: AvailableDevices) {
            Log.d(TAG, "onAvailableDevicesUpdated $availableDevices")
            populateSpinnerWithAvailableAudioDevices(availableDevices.audio)
        }
    }

    private fun onJoinedMeeting() {
        Log.i(TAG, "onJoinedMeeting")
        inCallButtons.visibility = View.VISIBLE
        choosePreferredRemoteParticipant()
        hideUrlBar()
        enableButtonHiding()
    }

    private fun hideUrlBar() {
        urlBar.visibility = View.GONE
    }

    private fun showUrlBar() {
        urlBar.visibility = View.VISIBLE
    }

    private fun enableButtonHiding() {
        // Hide the buttons after 3 seconds
        bottomToolbars.postDelayed(buttonHidingRunnable, 3000)
        buttonHidingEnabled = true
    }

    private fun onShowButtons() {
        if (buttonHidingEnabled) {
            disableButtonHiding()
            enableButtonHiding()
        }
    }

    private fun disableButtonHiding() {
        bottomToolbars.animate().cancel()
        bottomToolbars.visibility = View.VISIBLE
        bottomToolbars.alpha = 1.0f
        bottomToolbars.removeCallbacks(buttonHidingRunnable)
        buttonHidingEnabled = false
    }

    private fun setupParticipantSubscriptionProfiles() {
        lifecycleScope.launch {
            val subscriptionProfilesResult = callClient.updateSubscriptionProfiles(
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
            Log.d(TAG, "subscription profiles result $subscriptionProfilesResult")
        }
    }

    private fun resetAppState() {
        addurl.isEnabled = true
        joinButton.isEnabled = true
        inCallButtons.visibility = View.GONE
        clearRemoteVideoView()
        remoteVideoChoice = RemoteVideoChoice.Auto
        remoteCameraMaskView.text = resources.getText(R.string.join_meeting_instructions)
        showUrlBar()
        disableButtonHiding()

        if (!userToggledLocalPreview) {
            localVideoToggle.isChecked = true
        }
    }

    private fun isMediaAvailable(info: ParticipantVideoInfo?): Boolean {
        return when (info?.state) {
            MediaState.blocked, MediaState.off, MediaState.interrupted -> false
            MediaState.receivable, MediaState.loading, MediaState.playable -> true
            null -> false
        }
    }

    private fun choosePreferredRemoteParticipant() {

        val allParticipants = callClient.participants().all

        val participant: Participant?
        val track: MediaStreamTrack?
        val trackType: VideoTrackType?

        when (remoteVideoChoice) {

            RemoteVideoChoice.Hide -> {
                participant = null
                track = null
                trackType = null
            }

            RemoteVideoChoice.Auto -> {
                val participantWhoIsSharingScreen =
                    allParticipants.values.firstOrNull { isMediaAvailable(it.media?.screenVideo) }?.id

                val activeSpeaker = callClient.activeSpeaker()?.takeUnless { it.info.isLocal }?.id

                /*
                    The preference is:
                        - The participant who is sharing their screen
                        - The active speaker
                        - The last displayed remote participant
                        - Any remote participant who has their video opened
                */
                val participantId = participantWhoIsSharingScreen
                    ?: activeSpeaker
                    ?: displayedRemoteParticipant?.id
                    ?: callClient.participants().all.values.firstOrNull {
                        !it.info.isLocal && isMediaAvailable(it.media?.camera)
                    }?.id

                // Get the latest information about the participant
                participant = allParticipants[participantId]

                if (isMediaAvailable(participant?.media?.screenVideo)) {
                    track = participant?.media?.screenVideo?.track
                    trackType = VideoTrackType.ScreenShare
                } else if (isMediaAvailable(participant?.media?.camera)) {
                    track = participant?.media?.camera?.track
                    trackType = VideoTrackType.Camera
                } else {
                    track = null
                    trackType = null
                }
            }

            is RemoteVideoChoice.Track -> {

                val choice = remoteVideoChoice as RemoteVideoChoice.Track

                trackType = choice.trackType
                val participantId = choice.participantId
                participant = allParticipants[participantId]

                track = participant?.media?.run {
                    when (trackType) {
                        VideoTrackType.Camera -> camera.track
                        VideoTrackType.ScreenShare -> screenVideo.track
                    }
                }
            }
        }

        renderRemoteParticipant(participant, track, trackType)
    }

    private fun renderRemoteParticipant(
        participant: Participant?,
        track: MediaStreamTrack?,
        trackType: VideoTrackType?
    ) {
        Log.i(TAG, "Switching to remote participant: ${participant?.id}, $trackType")

        displayedRemoteParticipant = participant

        when (trackType ?: VideoTrackType.Camera) {
            VideoTrackType.Camera -> {
                remoteVideoView.videoScaleMode = VideoView.VideoScaleMode.FILL
                if (!userToggledLocalPreview) localVideoToggle.isChecked = true
            }
            VideoTrackType.ScreenShare -> {
                remoteVideoView.videoScaleMode = VideoView.VideoScaleMode.FIT
                if (!userToggledLocalPreview) localVideoToggle.isChecked = false
            }
        }

        remoteVideoView.track = track

        val containsTrack = track != null
        remoteCameraMaskView.visibility = if (containsTrack) View.GONE else View.VISIBLE
        remoteVideoView.visibility = if (containsTrack) View.VISIBLE else View.GONE

        updateRemoteCameraMaskViewMessage()
        changePreferredRemoteParticipantSubscription(participant, trackType)
    }

    private fun changePreferredRemoteParticipantSubscription(
        activeParticipant: Participant?,
        trackType: VideoTrackType?
    ) {
        lifecycleScope.launch {
            val subscriptionsResult = callClient.updateSubscriptions(
                // Improve the video quality of the remote participant that is currently displayed
                forParticipants = activeParticipant?.run {
                    mapOf(
                        id to SubscriptionSettingsUpdate(
                            profile = when (trackType) {
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
            Log.d(TAG, "Update subscriptions result $subscriptionsResult")
        }
    }

    private fun clearRemoteVideoView() {
        remoteVideoView.track = null
        remoteVideoView.visibility = View.GONE
        displayedRemoteParticipant = null
        remoteCameraMaskView.visibility = View.VISIBLE
    }

    private fun updateRemoteCameraMaskViewMessage() {
        val message =
            if (displayedRemoteParticipant != null)
                displayedRemoteParticipant?.info?.userName ?: "Guest"
            else
                when (val amountOfParticipants = callClient.participants().all.size) {
                    1 -> resources.getString(R.string.no_one_else_in_meeting)
                    2 -> callClient.participants().all.filter { !it.value.info.isLocal }.entries.first().value.info.userName
                    else -> resources.getString(R.string.amount_of_participants_at_meeting, amountOfParticipants - 1)
                }
        remoteCameraMaskView.text = message
    }

    private fun updateLocalParticipantVideoView(participant: Participant) {
        // make sure we are only handling local participants
        if (!participant.info.isLocal) {
            return
        }

        localVideoView.track = participant.media?.camera?.track
        localVideoView.visibility = View.VISIBLE
    }

    private fun updateParticipantVideoView(participant: Participant) {
        if (participant.info.isLocal) {
            updateLocalParticipantVideoView(participant)
        } else {
            choosePreferredRemoteParticipant()
        }
    }

    override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)

        prefs = Preferences(this)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urlBar = findViewById(R.id.url_bar)
        bottomToolbars = findViewById(R.id.bottom_toolbars)
        backgroundTapInterceptor = findViewById(R.id.background_tap_interceptor)

        joinButton = findViewById(R.id.call_button)
        leaveButton = findViewById(R.id.hangup_button)
        moreOptionsButton = findViewById(R.id.more_options_button)

        inCallButtons = findViewById(R.id.in_call_buttons)

        micInputButton = findViewById(R.id.input_mic_button)
        camInputButton = findViewById(R.id.input_camera_button)
        micPublishButton = findViewById(R.id.publish_mic_button)
        camPublishButton = findViewById(R.id.publish_camera_button)

        localContainer = findViewById(R.id.local_video_view_container)
        remoteContainer = findViewById(R.id.remote_video_view_container)

        localVideoView = findViewById(R.id.local_video_view)
        remoteVideoView = findViewById(R.id.remote_video_view)

        localCameraMaskView = findViewById(R.id.local_camera_mask_view)
        remoteCameraMaskView = findViewById(R.id.remote_camera_mask_view)

        addurl = findViewById(R.id.aurl)
        prefs.lastUrl?.apply {
            addurl.setText(this)
            joinButton.isEnabled = true
        }

        localVideoToggle = findViewById(R.id.local_video_toggle)

        localVideoToggle.setOnCheckedChangeListener { _, isChecked ->
            localContainer?.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        localVideoToggle.setOnClickListener {
            userToggledLocalPreview = true
        }

        moreOptionsButton.setOnClickListener {
            val menu = PopupMenu(this, moreOptionsButton)
            menu.inflate(R.menu.call_options)
            menu.show()

            menu.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.call_option_change_remote_participant -> {
                        showMenuChangeRemoteVideo()
                    }

                    else -> {
                        throw RuntimeException("Unhandled menu item: ${it.itemId}")
                    }
                }
                true
            }
        }

        //noinspection ClickableViewAccessibility
        backgroundTapInterceptor.setOnTouchListener { _, _ ->
            onShowButtons()
            false // Always return false so the touch passes through
        }

        audioDevicesSpinner = findViewById(R.id.audio_devices_spinner)

        checkPermissions()
    }

    private fun initialize() {
        initCallClient()
        initEventListeners()
        loadAudioDevice()
    }

    private enum class VideoTrackType {
        Camera,
        ScreenShare
    }

    private sealed class RemoteVideoChoice {
        object Hide : RemoteVideoChoice()
        object Auto : RemoteVideoChoice()
        data class Track(
            val participantId: ParticipantId,
            val trackType: VideoTrackType
        ) : RemoteVideoChoice()
    }

    private fun showMenuChangeRemoteVideo() {

        data class RemoteVideoMenuChoice(val name: String, val choice: RemoteVideoChoice)

        val choices = ArrayList<RemoteVideoMenuChoice>()

        callClient.participants().all.forEach {

            val username = if (it.value.info.isLocal) {
                getString(R.string.me)
            } else {
                (it.value.info.userName ?: it.key.uuid.toString())
            }

            if (isMediaAvailable(it.value.media?.camera)) {
                choices.add(
                    RemoteVideoMenuChoice(
                        resources.getString(R.string.username_with_camera, username),
                        RemoteVideoChoice.Track(it.key, VideoTrackType.Camera)
                    )
                )
            }

            if (isMediaAvailable(it.value.media?.screenVideo)) {
                choices.add(
                    RemoteVideoMenuChoice(
                        resources.getString(R.string.username_with_screen_share, username),
                        RemoteVideoChoice.Track(it.key, VideoTrackType.ScreenShare)
                    )
                )
            }
        }

        choices.sortBy { it.name }

        choices.add(
            0,
            RemoteVideoMenuChoice(
                getString(R.string.remote_video_hide),
                RemoteVideoChoice.Hide
            )
        )

        choices.add(
            1,
            RemoteVideoMenuChoice(
                getString(R.string.remote_video_active_speaker),
                RemoteVideoChoice.Auto
            )
        )

        val checkedItem = choices.indexOfFirst { it.choice == remoteVideoChoice }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.call_option_change_remote_video_title)
            .setSingleChoiceItems(
                choices.map { it.name }.toTypedArray(),
                checkedItem
            ) { _, which ->
                val item = choices[which]
                Log.i(TAG, "Selected remote track: ${item.name}")
                remoteVideoChoice = item.choice
                choosePreferredRemoteParticipant()
            }
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleCamInput(enabled: Boolean) {
        Log.d(TAG, "toggleCamInput $enabled")
        lifecycleScope.launch {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    camera = if (enabled) Enable() else Disable()
                )
            )
        }
    }

    private fun toggleMicInput(enabled: Boolean) {
        Log.d(TAG, "toggleMicInput $enabled")
        lifecycleScope.launch {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    microphone = if (enabled) Enable() else Disable()
                )
            )
        }
    }

    private fun toggleMicPublish(enabled: Boolean) {
        Log.d(TAG, "toggleMicPublish $enabled")
        lifecycleScope.launch {
            callClient.updatePublishing(
                publishSettings = PublishingSettingsUpdate(
                    microphone = MicrophonePublishingSettingsUpdate(
                        isPublishing = if (enabled) Enable() else Disable()
                    )
                )
            )
        }
    }

    private fun toggleCamPublish(enabled: Boolean) {
        Log.d(TAG, "toggleCamPublish $enabled")
        lifecycleScope.launch {
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
        micInputButton.isChecked = callClient.inputs().microphone.isEnabled
        camInputButton.isChecked = callClient.inputs().camera.isEnabled
        micPublishButton.isChecked = callClient.publishing().microphone.isPublishing
        camPublishButton.isChecked = callClient.publishing().camera.isPublishing
    }

    private fun createClientSettingsIntent(): ClientSettingsUpdate {
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
        val toastMessage: TextView = layout.findViewById(R.id.custom_toast_message)
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
                    prefs.lastUrl = url
                    callClient.join(url, clientSettingsIntent)
                    callClient.setUserName("Android User")
                    val audioDeviceInUse = callClient.audioDevice()
                    Log.d(TAG, "Current audio route $audioDeviceInUse")
                } catch (e: UnknownCallClientError) {
                    Log.e(TAG, "Failed to join call", e)
                    callClient.leave()
                    showMessage("Failed to join call")
                }
            }
        }

        leaveButton.setOnClickListener {
            inCallButtons.visibility = View.GONE
            callClient.leave()
        }

        micInputButton.setOnCheckedChangeListener { _, isChecked -> toggleMicInput(isChecked) }
        camInputButton.setOnCheckedChangeListener { _, isChecked -> toggleCamInput(isChecked) }
        micPublishButton.setOnCheckedChangeListener { _, isChecked -> toggleMicPublish(isChecked) }
        camPublishButton.setOnCheckedChangeListener { _, isChecked -> toggleCamPublish(isChecked) }
    }

    private fun initCallClient() {
        callClient = CallClient(applicationContext, this.lifecycle).apply {
            addListener(callClientListener)
        }

        // By default, we are always starting the demo app with the mic and camera on
        lifecycleScope.launch {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    microphone = Enable(),
                    camera = Enable()
                )
            )
            refreshInputPublishButtonsState()
        }

        setupParticipantSubscriptionProfiles()
    }

    private fun loadAudioDevice() {
        val audioDevices = callClient.availableDevices().audio
        populateSpinnerWithAvailableAudioDevices(audioDevices)
    }

    private fun populateSpinnerWithAvailableAudioDevices(audioDevices: List<MediaDeviceInfo>) {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            audioDevices.map {
                it.label
            }
        )
        audioDevicesSpinner.adapter = adapter
        audioDevicesSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val mediaDevice = audioDevices[position]
                lifecycleScope.launch() {
                    callClient.setAudioDevice(mediaDevice.deviceId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity on destroy has been invoked!")
        localVideoView.release()
        remoteVideoView.release()
    }

    private fun checkPermissions() {
        val permissionList = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions

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
