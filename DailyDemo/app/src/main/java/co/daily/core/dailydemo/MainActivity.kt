package co.daily.core.dailydemo

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import co.daily.core.dailydemo.chat.ChatActivity
import co.daily.core.dailydemo.chat.ChatProtocol
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooser
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserAuto
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserHide
import co.daily.core.dailydemo.remotevideochooser.RemoteVideoChooserManual
import co.daily.core.dailydemo.services.DemoActiveCallService
import co.daily.core.dailydemo.services.DemoCallService
import co.daily.model.CallState
import co.daily.model.MediaDeviceInfo
import co.daily.model.MeetingToken
import co.daily.model.RequestListener
import co.daily.view.VideoView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val TAG = "daily_demo_app"

class MainActivity : AppCompatActivity(), DemoStateListener {

    companion object {
        private const val REQUEST_CAPTURE = 1
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle the permission granted and media projection here
                val data: Intent? = result.data
                if (data != null) {
                    callService?.startScreenShare(data)
                }
            } else {
                Log.e(TAG, "Denied permission to start media projection.")
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            if (result.values.any { !it }) {
                Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_LONG).show()
            } else {
                // permission is granted, we can initialize
                initialize()
            }
        }

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to service")
            callService = service!! as DemoCallService.Binder
            callService?.addListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Disconnected from service")
            callService = null
        }
    }

    private var callService: DemoCallService.Binder? = null
    private var demoState: DemoState? = null

    private lateinit var prefs: Preferences

    private lateinit var layoutLoading: View
    private lateinit var layoutCall: View

    private lateinit var addurl: EditText
    private lateinit var meetingTokenInput: EditText
    private lateinit var usernameInput: EditText

    private lateinit var localContainer: FrameLayout
    private lateinit var remoteContainer: FrameLayout
    private var localVideoView: VideoView? = null
    private var remoteVideoView: VideoView? = null

    private lateinit var urlBar: View
    private lateinit var bottomToolbars: View
    private lateinit var backgroundTapInterceptor: View

    private lateinit var inCallButtons: View

    private lateinit var joinButton: Button
    private lateinit var leaveButton: ImageButton
    private lateinit var moreOptionsButton: ImageButton

    private lateinit var micInputButton: ToggleButton
    private lateinit var camInputButton: ToggleButton
    private lateinit var camInputFlipButton: Button
    private lateinit var micPublishButton: ToggleButton
    private lateinit var camPublishButton: ToggleButton
    private lateinit var audioDevicesSpinner: Spinner

    private lateinit var localVideoToggle: ToggleButton

    private lateinit var localCameraMaskView: TextView
    private lateinit var remoteCameraMaskView: TextView

    private lateinit var recentChatMessages: LinearLayoutCompat

    private var buttonHidingEnabled = false
    private val buttonHidingRunnable: Runnable = Runnable {
        bottomToolbars.animate()
            .alpha(0.0f)
            .setDuration(500)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bottomToolbars.visibility = View.GONE
                }
            })
    }

    // Set this to true to disable automatic show/hide of local video view
    private var userToggledLocalPreview: Boolean = false

    private var triggeredForegroundService = false

    private fun updateLocalVideoState() {

        // Due to a bug in older versions of Android (including Android 9), it's not
        // sufficient to simply hide a SurfaceView if it overlaps with another
        // SurfaceView, so we destroy and recreate it as necessary.

        fun hideLocalVideoView() {
            localVideoView?.apply {
                localContainer.removeView(this)
            }
            localVideoView = null
            localCameraMaskView.visibility = View.VISIBLE
        }

        val track = demoState?.localParticipantTrack

        if (localVideoToggle.isChecked) {

            localContainer.visibility = View.VISIBLE

            if (track != null && demoState?.inputs?.cameraEnabled == true) {
                val view: VideoView = localVideoView ?: VideoView(this@MainActivity).apply {
                    localVideoView = this
                    bringVideoToFront = true
                    localContainer.addView(this)
                }
                view.track = track
                localCameraMaskView.visibility = View.GONE
            } else {
                hideLocalVideoView()
            }
        } else {
            localContainer.visibility = View.GONE
            hideLocalVideoView()
        }
    }

    private fun enableButtonHiding() {
        if (!buttonHidingEnabled) {
            bottomToolbars.postDelayed(buttonHidingRunnable, 3000)
            buttonHidingEnabled = true
        }
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

    private fun updateRemoteVideoState(choice: RemoteVideoChooser.Choice?) {

        val track = choice?.track

        if (track != null && demoState?.status == CallState.joined) {

            Log.i(
                TAG,
                "Switching to remote participant: ${choice.participant?.id}, $choice.trackType"
            )

            val view = remoteVideoView ?: VideoView(this).apply {
                remoteContainer.addView(this)
                remoteVideoView = this
                background = ColorDrawable(Color.DKGRAY)
            }

            when (choice.trackType ?: VideoTrackType.Camera) {
                VideoTrackType.Camera -> {
                    view.videoScaleMode = VideoView.VideoScaleMode.FILL
                    if (!userToggledLocalPreview) localVideoToggle.isChecked = true
                }
                else -> {
                    view.videoScaleMode = VideoView.VideoScaleMode.FIT
                    if (!userToggledLocalPreview) localVideoToggle.isChecked = false
                }
            }

            view.track = choice.track
            remoteCameraMaskView.visibility = View.GONE
        } else {
            Log.i(TAG, "Hiding remote video view")
            remoteContainer.removeView(remoteVideoView)
            remoteVideoView = null
            remoteCameraMaskView.visibility = View.VISIBLE
        }
    }

    private fun updateRemoteCameraMaskViewMessage() {

        val state = demoState

        remoteCameraMaskView.text = when (state?.status) {
            CallState.initialized, CallState.leaving, CallState.left -> resources.getString(R.string.join_meeting_instructions)
            CallState.joining -> resources.getString(R.string.joining)

            CallState.joined -> if (state.displayedRemoteParticipant?.participant != null) {
                state.displayedRemoteParticipant.participant.info.userName ?: "Guest"
            } else {
                val participants = state.allParticipants
                when (val amountOfParticipants = participants.size) {
                    1 -> resources.getString(R.string.no_one_else_in_meeting)
                    2 -> participants.filter { !it.value.info.isLocal }.entries.first().value.info.userName
                    else -> resources.getString(
                        R.string.amount_of_participants_at_meeting,
                        amountOfParticipants - 1
                    )
                }
            }

            null -> "Unknown state: waiting for service"
        }
    }

    override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)

        prefs = Preferences(this)

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        layoutLoading = findViewById(R.id.main_activity_loading_layout)
        layoutCall = findViewById(R.id.main_activity_call_layout)

        urlBar = findViewById(R.id.url_bar)
        bottomToolbars = findViewById(R.id.bottom_toolbars)
        backgroundTapInterceptor = findViewById(R.id.background_tap_interceptor)

        joinButton = findViewById(R.id.call_button)
        leaveButton = findViewById(R.id.hangup_button)
        moreOptionsButton = findViewById(R.id.more_options_button)

        inCallButtons = findViewById(R.id.in_call_buttons)

        micInputButton = findViewById(R.id.input_mic_button)
        camInputButton = findViewById(R.id.input_camera_button)
        camInputFlipButton = findViewById(R.id.input_camera_button_flip)
        micPublishButton = findViewById(R.id.publish_mic_button)
        camPublishButton = findViewById(R.id.publish_camera_button)

        localContainer = findViewById(R.id.local_video_view_container)
        remoteContainer = findViewById(R.id.remote_video_view_container)

        localCameraMaskView = findViewById(R.id.local_camera_mask_view)
        remoteCameraMaskView = findViewById(R.id.remote_camera_mask_view)

        recentChatMessages = findViewById(R.id.recent_chat_messages)

        addurl = findViewById(R.id.aurl)
        meetingTokenInput = findViewById(R.id.meeting_token_input)
        usernameInput = findViewById(R.id.username_input)

        prefs.lastUrl?.apply {
            addurl.setText(this)
            updateJoinButtonState()
        }

        prefs.lastUsername?.apply {
            usernameInput.setText(this)
        }

        localVideoToggle = findViewById(R.id.local_video_toggle)

        localVideoToggle.setOnCheckedChangeListener { _, _ ->
            updateLocalVideoState()
        }

        localVideoToggle.setOnClickListener {
            userToggledLocalPreview = true
        }

        moreOptionsButton.setOnClickListener {
            val menu = PopupMenu(this, moreOptionsButton)
            menu.inflate(R.menu.call_options)

            val screenShareEnabled = demoState?.inputs?.screenVideoEnabled ?: false
            menu.menu.findItem(R.id.call_option_stop_screen_share)?.isVisible = screenShareEnabled
            menu.menu.findItem(R.id.call_option_start_screen_share)?.isVisible = !screenShareEnabled

            menu.show()

            menu.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.call_option_change_remote_participant -> {
                        showMenuChangeRemoteVideo()
                    }

                    R.id.call_option_start_screen_share -> {
                        startScreenShare()
                    }

                    R.id.call_option_stop_screen_share -> {
                        callService?.stopScreenShare()
                    }

                    R.id.call_option_developer_options -> {
                        callService?.showDeveloperOptionsDialog(this)
                    }

                    R.id.call_option_chat -> {
                        startActivity(Intent(this@MainActivity, ChatActivity::class.java))
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

        // initializing the mediaProjectionManager that we are going to use later to ask for screen share
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        checkPermissions()
    }

    private fun initialize() {

        if (!bindService(
                Intent(
                        this,
                        DemoCallService::class.java
                    ),
                serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        ) {
            throw RuntimeException("Failed to bind to call service")
        }

        initEventListeners()
    }

    private fun showMenuChangeRemoteVideo() {

        data class RemoteVideoMenuChoice(val name: String, val choice: RemoteVideoChooser)

        val choices = ArrayList<RemoteVideoMenuChoice>()

        demoState?.allParticipants?.forEach {

            val username = if (it.value.info.isLocal) {
                getString(R.string.me)
            } else {
                (it.value.info.userName ?: it.key.uuid.toString())
            }

            if (Utils.isMediaAvailable(it.value.media?.camera)) {
                choices.add(
                    RemoteVideoMenuChoice(
                        resources.getString(R.string.username_with_camera, username),
                        RemoteVideoChooserManual(it.key, VideoTrackType.Camera)
                    )
                )
            }

            if (Utils.isMediaAvailable(it.value.media?.screenVideo)) {
                choices.add(
                    RemoteVideoMenuChoice(
                        resources.getString(R.string.username_with_screen_share, username),
                        RemoteVideoChooserManual(it.key, VideoTrackType.ScreenShare)
                    )
                )
            }

            it.value.media?.customVideo?.forEach { (name, media) ->
                if (Utils.isMediaAvailable(media)) {
                    choices.add(
                        RemoteVideoMenuChoice(
                            resources.getString(R.string.username_with_custom_track, username, name.name),
                            RemoteVideoChooserManual(it.key, VideoTrackType.CustomTrack(name))
                        )
                    )
                }
            }
        }

        choices.sortBy { it.name }

        choices.add(
            0,
            RemoteVideoMenuChoice(
                getString(R.string.remote_video_active_speaker),
                RemoteVideoChooserAuto
            )
        )

        choices.add(
            1,
            RemoteVideoMenuChoice(
                getString(R.string.remote_video_hide),
                RemoteVideoChooserHide
            )
        )

        val checkedItem = choices.indexOfFirst { it.choice == demoState?.remoteVideoChooser }
            .coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.call_option_change_remote_video_title)
            .setSingleChoiceItems(
                choices.map { it.name }.toTypedArray(),
                checkedItem
            ) { _, which ->
                val item = choices[which]
                Log.i(TAG, "Selected remote track: ${item.name}")
                callService?.setRemoteVideoChooser(item.choice)
            }
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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

    private fun updateJoinButtonState() {
        joinButton.isEnabled = when (demoState?.status) {
            CallState.initialized, CallState.left -> {
                Patterns.WEB_URL.matcher(addurl.text.toString()).matches()
            }
            CallState.joined, CallState.joining, CallState.leaving -> false
            null -> false
        }
    }

    private fun initEventListeners() {
        addurl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateJoinButtonState()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        joinButton.setOnClickListener {
            joinButton.isEnabled = false
            addurl.isEnabled = false
            meetingTokenInput.isEnabled = false
            usernameInput.isEnabled = false
            val url = addurl.text.toString()
            val username = usernameInput.text?.toString()?.takeUnless { it.isEmpty() }

            prefs.lastUrl = url
            prefs.lastUsername = username

            val token = meetingTokenInput.text?.toString()?.takeUnless {
                it.isEmpty()
            }?.run { MeetingToken(this) }

            callService!!.setUsername(username ?: "Android Demo User")
            callService!!.join(url, token)
        }

        fun setButtonListenerWithDisable(button: View, action: (RequestListener) -> Unit) {
            button.setOnClickListener {
                button.isEnabled = false
                action {
                    button.isEnabled = true
                }
            }
        }

        setButtonListenerWithDisable(leaveButton) { listener ->
            callService!!.leave { result ->
                listener.onRequestResult(result)
                inCallButtons.visibility = View.GONE
            }
        }

        setButtonListenerWithDisable(micInputButton) {
            callService?.toggleMicInput(micInputButton.isChecked, it)
        }

        setButtonListenerWithDisable(camInputButton) {
            callService?.toggleCamInput(camInputButton.isChecked, it)
        }

        setButtonListenerWithDisable(camInputFlipButton) {
            callService?.flipCameraDirection(it)
        }

        setButtonListenerWithDisable(micPublishButton) {
            callService?.toggleMicPublishing(micPublishButton.isChecked, it)
        }

        setButtonListenerWithDisable(camPublishButton) {
            callService?.toggleCamPublishing(camPublishButton.isChecked, it)
        }
    }

    private fun startScreenShare() {
        val mediaProjectionIntent = mediaProjectionManager.createScreenCaptureIntent()
        requestMediaProjection.launch(mediaProjectionIntent)
    }

    private fun populateSpinnerWithAvailableAudioDevices(audioDevices: List<MediaDeviceInfo>) {

        val selectedDeviceIndex = audioDevices.indexOfFirst {
            it.deviceId == demoState?.activeAudioDevice
        }.takeUnless { it == -1 }

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_dropdown_item_1line,
            audioDevices.map {
                it.label
            }
        )
        audioDevicesSpinner.adapter = adapter

        audioDevicesSpinner.setSelection(selectedDeviceIndex ?: 0)

        audioDevicesSpinner.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                callService?.setAudioDevice(audioDevices[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity on destroy has been invoked!")
        callService?.removeListener(this)
        unbindService(serviceConnection)
    }

    private fun checkPermissions() {
        val permissionList = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissionList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

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

    override fun onStateChanged(newState: DemoState) {
        Log.i(TAG, "onCallStateChanged: $newState")

        demoState = newState

        layoutLoading.visibility = View.GONE
        layoutCall.visibility = View.VISIBLE

        updateLocalVideoState()
        updateRemoteVideoState(newState.displayedRemoteParticipant)

        populateSpinnerWithAvailableAudioDevices(newState.availableDevices.audio)

        micInputButton.isChecked = newState.inputs.micEnabled
        camInputButton.isChecked = newState.inputs.cameraEnabled
        micPublishButton.isChecked = newState.publishing.micEnabled
        camPublishButton.isChecked = newState.publishing.cameraEnabled

        updateJoinButtonState()
        updateRemoteCameraMaskViewMessage()

        when (newState.status) {
            CallState.initialized, CallState.left -> {
                inCallButtons.visibility = View.GONE
                urlBar.visibility = View.VISIBLE
                addurl.isEnabled = true
                meetingTokenInput.isEnabled = true
                usernameInput.isEnabled = true
                disableButtonHiding()

                if (!userToggledLocalPreview) {
                    localVideoToggle.isChecked = true
                }

                triggeredForegroundService = false
            }
            CallState.joining -> {
                inCallButtons.visibility = View.GONE
                urlBar.visibility = View.VISIBLE
                addurl.isEnabled = false
                meetingTokenInput.isEnabled = false
                usernameInput.isEnabled = false
                disableButtonHiding()
            }
            CallState.leaving -> {
                inCallButtons.visibility = View.VISIBLE
                urlBar.visibility = View.GONE
                disableButtonHiding()
            }
            CallState.joined -> {
                inCallButtons.visibility = View.VISIBLE
                urlBar.visibility = View.GONE
                enableButtonHiding()

                if (!triggeredForegroundService) {

                    // Start the foreground service to keep the call alive

                    Log.i(TAG, "Starting foreground service")

                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, DemoActiveCallService::class.java)
                    )

                    triggeredForegroundService = true
                }
            }
        }
    }

    override fun onError(msg: String) {
        Log.e(TAG, "Got error: $msg")
        showMessage(msg)
    }

    override fun onChatRemoteMessageReceived(chatMessage: ChatProtocol.ChatMessage) {
        val view = layoutInflater.inflate(R.layout.chat_message_popup, recentChatMessages, false)

        view.findViewById<TextView>(R.id.chat_message_author).text = chatMessage.name
        view.findViewById<TextView>(R.id.chat_message_content).text = chatMessage.message

        view.setOnClickListener {
            startActivity(Intent(this@MainActivity, ChatActivity::class.java))
        }

        recentChatMessages.addView(view)

        // Hide the popup after 5 seconds
        Utils.UI_THREAD_HANDLER.postDelayed({
            recentChatMessages.removeView(view)
        }, 5000)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
