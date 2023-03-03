package co.daily.core.dailydemo.chat

import android.util.Log
import co.daily.model.CallState
import co.daily.model.ParticipantId
import co.daily.model.Participants
import co.daily.model.Recipient
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "ChatProtocol"

class ChatProtocol(
    private val listener: ChatProtocolListener,
    private val helper: ChatProtocolHelper
) {

    companion object {
        private val dateFormat: DateFormat

        @OptIn(ExperimentalSerializationApi::class)
        private val jsonIgnoreUnknownKeys = Json { ignoreUnknownKeys = true; explicitNulls = true }

        init {
            dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private val historySyncMessages = ArrayList<StoredChatMessage>()
    val messages = ArrayList<StoredChatMessage>()

    private val syncHistoryCandidates = HashSet<ParticipantId>()

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("event")
    sealed class AppMessage

    @Serializable
    @SerialName("chat-msg")
    private data class AppMessageChatMessage(
        val date: String,
        val message: String,
        val name: String,
        val room: String? = null
    ) : AppMessage()

    @Serializable
    @SerialName("request-chat-history")
    private object AppMessageRequestChatHistory : AppMessage()

    @Serializable
    @SerialName("sync-chat-msg")
    private data class AppMessageSyncChatMessage(
        val message: ChatMessage
    ) : AppMessage()

    @Serializable
    data class ChatMessage(
        val date: String,
        val fromId: ParticipantId,
        val message: String,
        val name: String
    )

    @Serializable
    @SerialName("sync-chat-complete")
    private object AppMessageSyncChatComplete : AppMessage()

    data class StoredChatMessage(
        val message: ChatMessage,
        val isLocal: Boolean
    )

    interface ChatProtocolHelper {
        fun sendAppMessage(msg: AppMessage, recipient: Recipient)
        fun participants(): Participants?
    }

    interface ChatProtocolListener {
        fun onMessageListUpdated()
        fun onRemoteMessageReceived(chatMessage: ChatMessage)
    }

    fun onAppMessageReceived(jsonString: String, fromId: ParticipantId) {

        try {
            val msg: AppMessage = jsonIgnoreUnknownKeys.decodeFromString(jsonString)

            when (msg) {
                is AppMessageRequestChatHistory -> {
                    // Not currently supported. To safely do this we should support reactions,
                    // otherwise these won't get sent in the history.
                }
                is AppMessageSyncChatComplete -> {
                    syncHistoryCandidates.clear()
                    messages.addAll(historySyncMessages)
                    historySyncMessages.clear()
                    messages.sortBy { it.message.date }
                    listener.onMessageListUpdated()
                }
                is AppMessageChatMessage -> {

                    val chatMessage = StoredChatMessage(
                        message = ChatMessage(
                            date = msg.date,
                            fromId = fromId,
                            message = msg.message,
                            name = msg.name
                        ),
                        isLocal = false
                    )

                    messages.add(chatMessage)

                    listener.onMessageListUpdated()
                    listener.onRemoteMessageReceived(chatMessage.message)
                }
                is AppMessageSyncChatMessage -> {
                    historySyncMessages.add(
                        StoredChatMessage(
                            message = msg.message,
                            isLocal = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode incoming app message '$jsonString'", e)
        }
    }

    fun onCallStateUpdated(callState: CallState) {
        if (callState == CallState.joined) {
            messages.clear()
            syncHistoryCandidates.clear()
            historySyncMessages.clear()

            helper.participants()?.run {
                all.values
                    .filter { !it.info.isLocal }
                    .map { it.id }
                    .forEach(syncHistoryCandidates::add)
            }

            MainScope().launch {

                while (true) {

                    val participant = syncHistoryCandidates.randomOrNull() ?: return@launch

                    Log.i(TAG, "Attempting to fetch history from $participant")

                    syncHistoryCandidates.remove(participant)

                    helper.sendAppMessage(
                        AppMessageRequestChatHistory,
                        Recipient.Participant(participant)
                    )

                    // Wait before trying another participant
                    delay(1000)
                }
            }
        } else if (callState == CallState.left) {
            syncHistoryCandidates.clear()
        }
    }

    fun sendMessage(content: String) {

        val localParticipant = helper.participants()?.local

        if (localParticipant == null) {
            Log.e(TAG, "Local participant was null when sending chat message")
            return
        }

        val username = localParticipant.info.userName ?: "Guest"
        val date = dateFormat.format(Date())

        val rawMessage = AppMessageChatMessage(
            date = date,
            message = content,
            name = username,
            room = "main-room"
        )

        helper.sendAppMessage(rawMessage, Recipient.All)

        messages.add(
            StoredChatMessage(
                message = ChatMessage(
                    date = date,
                    fromId = localParticipant.id,
                    message = content,
                    name = username
                ),
                isLocal = true
            )
        )

        listener.onMessageListUpdated()
    }
}
