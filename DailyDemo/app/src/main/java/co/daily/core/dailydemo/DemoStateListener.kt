package co.daily.core.dailydemo

import co.daily.core.dailydemo.chat.ChatProtocol

interface DemoStateListener {
    fun onStateChanged(newState: DemoState) {}
    fun onError(msg: String) {}
    fun onChatMessageListUpdated() {}
    fun onChatRemoteMessageReceived(chatMessage: ChatProtocol.ChatMessage) {}
}
