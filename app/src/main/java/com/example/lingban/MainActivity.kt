package com.example.lingban

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // DeepSeek API Key（已内置）
    private val apiKey = "sk-cfe77eee81e7467a819fa12da293febf"
    private val baseUrl = "https://api.deepseek.com"
    private val modelName = "deepseek-chat"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var db: AppDatabase
    private lateinit var shortcutContainer: LinearLayout
    private val prefs by lazy { getSharedPreferences("user_prefs", Context.MODE_PRIVATE) }

    // 对话历史（已持久化到 SharedPreferences）
    private val chatHistory = mutableListOf<JSONObject>()

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
        loadChatContext()    // 恢复保存的对话历史
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

        // 长按删除消息
        adapter.setOnItemLongClickListener { position ->
            AlertDialog.Builder(this)
                .setTitle("删除消息")
                .setMessage("确定要删除这条消息吗？")
                .setPositiveButton("删除") { _, _ ->
                    val msg = adapter.messages[position]
                    adapter.messages.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    lifecycleScope.launch {
                        db.messageDao().deleteMessage(msg.id)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
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
        val shortcuts = mutableListOf(
            "社交助手" to "open_social",           // 跳转社交助手页面
            "回复简洁一点" to "记住我喜欢简洁的回复风格",
            "专业模式" to "记住我偏好正式、专业的语气",
            "温柔一点" to "记住我喜欢温柔、关心的语气",
            "恢复正常" to "重置偏好，正常回复"
        )
        shortcutContainer.removeAllViews()
        for ((label, action) in shortcuts) {
            val chip = TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.on_surface))
                setBackgroundResource(R.drawable.shortcut_chip_bg)
                setPadding(24, 8, 24, 8)
                textSize = 13f
                setOnClickListener {
                    if (action == "open_social") {
                        startActivity(Intent(this@MainActivity, SocialActivity::class.java))
                    } else {
                        messageInput.setText(label)
                    }
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            shortcutContainer.addView(chip, params)
        }
    }

    private fun handleUserMessage(text: String) {
        // 保存用户消息到数据库
        val userMsg = MessageEntity(text = text, isUser = true)
        lifecycleScope.launch {
            val id = db.messageDao().insertMessage(userMsg)
            withContext(Dispatchers.Main) {
                adapter.messages.add(Message(text, true, id))
                adapter.notifyItemInserted(adapter.messages.size - 1)
                recyclerView.scrollToPosition(adapter.messages.size - 1)
            }
        }

        if (text.contains("记住") || text.startsWith("偏好")) {
            prefs.edit().putString("preference", text).apply()
            addBotMessage("已记住你的偏好：$text")
        } else {
            askDeepSeek(text)
        }
    }

    private fun askDeepSeek(userMessage: String) {
        addBotMessage("思考中...")
        val loadingIndex = adapter.messages.size - 1

        lifecycleScope.launch {
            try {
                val messagesArray = JSONArray()

                // 系统提示
                val preference = prefs.getString("preference", "") ?: ""
                val systemPrompt = if (preference.isNotEmpty()) {
                    "你是一个私人助手，用户的偏好是：$preference。请根据此偏好回复。"
                } else {
                    "你是一个贴心的私人助手。"
                }
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })

                // 添加最近几轮对话历史
                for (msg in chatHistory.takeLast(6)) {
                    messagesArray.put(msg)
                }

                // 当前用户消息
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })

                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messagesArray)
                    put("temperature", 0.7)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = requestBody.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val responseStr = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("服务器错误: ${response.code}")
                        }
                        response.body?.string() ?: throw Exception("响应为空")
                    }
                }

                val jsonResponse = JSONObject(responseStr)
                val choices = jsonResponse.getJSONArray("choices")
                val reply = if (choices.length() > 0) {
                    choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else {
                    "抱歉，我没有得到回复。"
                }

                // 更新对话历史并持久化
                chatHistory.add(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
                chatHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", reply)
                })
                if (chatHistory.size > 20) {
                    chatHistory.removeAt(0)
                }
                saveChatContext()

                // 替换加载提示为真实回复
                withContext(Dispatchers.Main) {
                    adapter.messages.removeAt(loadingIndex)
                    adapter.notifyItemRemoved(loadingIndex)
                    addBotMessage(reply)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (adapter.messages.size > loadingIndex) {
                        adapter.messages.removeAt(loadingIndex)
                        adapter.notifyItemRemoved(loadingIndex)
                    }
                    addBotMessage("出错啦：${e.message}")
                }
            }
        }
    }

    private fun addBotMessage(text: String) {
        lifecycleScope.launch {
            val botMsg = MessageEntity(text = text, isUser = false)
            val id = db.messageDao().insertMessage(botMsg)
            withContext(Dispatchers.Main) {
                adapter.messages.add(Message(text, false, id))
                adapter.notifyItemInserted(adapter.messages.size - 1)
                recyclerView.scrollToPosition(adapter.messages.size - 1)
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = db.messageDao().getAllMessages()
            val messages = history.map { Message(it.text, it.isUser, it.id) }.toMutableList()
            withContext(Dispatchers.Main) {
                adapter.messages.clear()
                adapter.messages.addAll(messages)
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) recyclerView.scrollToPosition(messages.size - 1)
            }
        }
    }

    // ---------- 记忆持久化核心 ----------
    private fun saveChatContext() {
        val jsonArray = JSONArray()
        for (msg in chatHistory.takeLast(10)) {
            jsonArray.put(msg)
        }
        prefs.edit().putString("chat_context", jsonArray.toString()).apply()
    }

    private fun loadChatContext() {
        val saved = prefs.getString("chat_context", null) ?: return
        try {
            val jsonArray = JSONArray(saved)
            chatHistory.clear()
            for (i in 0 until jsonArray.length()) {
                chatHistory.add(jsonArray.getJSONObject(i))
            }
        } catch (_: Exception) { }
    }
}
