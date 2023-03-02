package co.daily.core.dailydemo.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import co.daily.core.dailydemo.DemoStateListener
import co.daily.core.dailydemo.R
import co.daily.core.dailydemo.services.DemoCallService
import com.google.android.material.button.MaterialButton

private const val TAG = "ChatActivity"

class ChatActivity : AppCompatActivity(), DemoStateListener {

    private var callService: DemoCallService.Binder? = null

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to service")
            callService = service!! as DemoCallService.Binder
            callService?.addListener(this@ChatActivity)

            historyAdapter.notifyDataSetChanged()
            scrollToEnd()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Disconnected from service")
            callService?.removeListener(this@ChatActivity)
            callService = null
        }
    }

    private val historyAdapter = HistoryAdapter()
    private lateinit var layoutManager: LinearLayoutManager

    private sealed class HistoryItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(author: String, content: String)
    }

    private class HistoryItemViewHolderHeader(parent: ViewGroup) : HistoryItemViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.chat_start, parent, false)
    ) {
        override fun bind(author: String, content: String) {
            throw RuntimeException("Attempted to bind message contents to header")
        }
    }

    private class HistoryItemViewHolderMessageLocal(parent: ViewGroup) : HistoryItemViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.chat_message_local, parent, false)
    ) {
        private val authorView: AppCompatTextView = itemView.findViewById(R.id.chat_message_author)
        private val contentView: AppCompatTextView = itemView.findViewById(R.id.chat_message_content)

        override fun bind(author: String, content: String) {
            authorView.text = author
            contentView.text = content
        }
    }

    private class HistoryItemViewHolderMessageRemote(parent: ViewGroup) : HistoryItemViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.chat_message_remote, parent, false)
    ) {
        private val authorView: AppCompatTextView = itemView.findViewById(R.id.chat_message_author)
        private val contentView: AppCompatTextView = itemView.findViewById(R.id.chat_message_content)

        override fun bind(author: String, content: String) {
            authorView.text = author
            contentView.text = content
        }
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryItemViewHolder>() {

        private val VIEW_TYPE_HEADER = 0
        private val VIEW_TYPE_MESSAGE_LOCAL = 1
        private val VIEW_TYPE_MESSAGE_REMOTE = 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
            VIEW_TYPE_HEADER -> HistoryItemViewHolderHeader(parent)
            VIEW_TYPE_MESSAGE_LOCAL -> HistoryItemViewHolderMessageLocal(parent)
            VIEW_TYPE_MESSAGE_REMOTE -> HistoryItemViewHolderMessageRemote(parent)
            else -> throw RuntimeException("Unknown view type $viewType")
        }

        override fun onBindViewHolder(holder: HistoryItemViewHolder, position: Int) {
            if (position > 0) {
                getItemAt(position)?.message?.apply {
                    holder.bind(author = name, content = message)
                }
            }
        }

        override fun getItemCount() = (callService?.chatMessages?.size ?: 0) + 1

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) VIEW_TYPE_HEADER else {
                if (getItemAt(position)!!.isLocal) {
                    VIEW_TYPE_MESSAGE_LOCAL
                } else {
                    VIEW_TYPE_MESSAGE_REMOTE
                }
            }
        }

        private fun getItemAt(position: Int) = callService?.chatMessages?.get(position - 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.chat_layout)

        val history: RecyclerView = findViewById(R.id.chat_messages)!!

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        layoutManager.stackFromEnd = true

        history.adapter = historyAdapter
        history.layoutManager = layoutManager

        val chatSendInput: AppCompatEditText = findViewById(R.id.chat_send_input)
        val chatSendButton: MaterialButton = findViewById(R.id.chat_send_button)

        val sendMsg = {
            chatSendInput.text?.takeUnless { it.isEmpty() }?.toString()?.apply {
                callService?.sendChatMessage(this)
                chatSendInput.text?.clear()
            }
        }

        chatSendButton.setOnClickListener { sendMsg() }
        chatSendInput.setOnEditorActionListener { v, action, event ->
            sendMsg()
            true
        }

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
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
    }

    private fun scrollToEnd() {
        // Includes the +1 due to the header
        layoutManager.scrollToPosition(callService?.chatMessages?.size ?: 0)
    }

    override fun onChatMessageListUpdated() {
        historyAdapter.notifyDataSetChanged()
        scrollToEnd()
    }
}
