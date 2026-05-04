package com.example.lingban

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var db: AppDatabase
    private lateinit var shortcutContainer: LinearLayout

    // 偏好存储
    private val prefs by lazy { getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_LingBan)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        db = AppDatabase.getDatabase(this)
        initViews()
        setupRecyclerView()
        setupSendButton()
        setupShortcuts()
        loadHistory()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recycler_view)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        shortcutContainer = findViewById(R.id.shortcut_container)
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSendButton() {
        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                handleUserMessage(text)
                messageInput.text.clear()
            }
        }
    }

    private fun setupShortcuts() {
        val shortcuts = listOf(
            "回复简洁一点", "专业模式", "温柔一点", "恢复正常"
        )
        shortcutContainer.removeAllViews()
        for (label in shortcuts) {
            val chip = TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.shortcut_chip_bg)
                setPadding(24, 8, 24, 8)
                textSize = 13f
                setOnClickListener { messageInput.setText(label) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            shortcutContainer.addView(chip, params)
        }
    }

    private fun handleUserMessage(text: String) {
        val userMsg = MessageEntity(text = text, isUser = true)
        lifecycleScope.launch { db.messageDao().insertMessage(userMsg) }
        adapter.messages.add(Message(text, true))
        adapter.notifyItemInserted(adapter.messages.size - 1)
        recyclerView.scrollToPosition(adapter.messages.size - 1)

        // 偏好识别与持久化
        if (text.contains("记住") || text.startsWith("偏好")) {
            prefs.edit().putString("preference", text).apply()
            addBotMessage("已记住你的偏好：$text")
        } else {
            addBotMessage(generateResponse(text))
        }
    }

    private fun generateResponse(input: String): String {
        val preference = prefs.getString("preference", "") ?: ""
        return when {
            preference.contains("简洁") -> "好的。"
            preference.contains("专业") -> "收到，我会以专业的角度处理您的请求。"
            preference.contains("温柔") -> "嗯嗯，我知道了～ 放心吧 😊"
            else -> "这是一个模拟回复：你说的是“$input”"
        }
    }

    private fun addBotMessage(text: String) {
        lifecycleScope.launch {
            db.messageDao().insertMessage(MessageEntity(text = text, isUser = false))
            withContext(Dispatchers.Main) {
                adapter.messages.add(Message(text, false))
                adapter.notifyItemInserted(adapter.messages.size - 1)
                recyclerView.scrollToPosition(adapter.messages.size - 1)
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = db.messageDao().getAllMessages()
            val messages = history.map { Message(it.text, it.isUser) }.toMutableList()
            withContext(Dispatchers.Main) {
                adapter.messages.clear()
                adapter.messages.addAll(messages)
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) recyclerView.scrollToPosition(messages.size - 1)
            }
        }
    }
}
