package com.example.lingban

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SocialActivity : AppCompatActivity() {

    private lateinit var inputMessage: EditText
    private lateinit var btnGenerate: ImageButton
    private lateinit var replyContainer: LinearLayout

    // 复用同一个 API Key，建议提取，这里直接写
    private val apiKey = "sk-cfe77eee81e7467a819fa12da293febf"
    private val baseUrl = "https://api.deepseek.com"
    private val modelName = "deepseek-chat"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_social)
        inputMessage = findViewById(R.id.input_message)
        btnGenerate = findViewById(R.id.btn_generate)
        replyContainer = findViewById(R.id.reply_container)

        btnGenerate.setOnClickListener {
            val text = inputMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                generateReplies(text)
            }
        }
    }

    private fun generateReplies(userMessage: String) {
        // 显示加载状态
        replyContainer.removeAllViews()
        val loading = TextView(this).apply {
            text = "正在生成建议..."
            setTextColor(getColor(R.color.on_surface))
            textSize = 14f
        }
        replyContainer.addView(loading)

        lifecycleScope.launch {
            try {
                // 构建 prompt
                val systemPrompt = """你是一个高情商社交助手。用户会给你一条收到的消息，请你生成三种风格的回复：
1. 简洁版：简短、直接
2. 友好版：热情、亲切
3. 专业版：正式、得体
请用如下格式输出（严格遵守，不要输出多余内容）：
简洁版：<回复内容>
友好版：<回复内容>
专业版：<回复内容>"""

                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
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
                        if (!response.isSuccessful) throw Exception("请求失败: ${response.code}")
                        response.body?.string() ?: throw Exception("响应为空")
                    }
                }

                val jsonResponse = JSONObject(responseStr)
                val choices = jsonResponse.getJSONArray("choices")
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                // 解析三种回复
                val replies = parseReplies(content)

                withContext(Dispatchers.Main) {
                    replyContainer.removeAllViews()
                    replies.forEach { reply ->
                        val chip = TextView(this@SocialActivity).apply {
                            text = reply
                            setTextColor(getColor(R.color.on_surface))
                            setBackgroundResource(R.drawable.shortcut_chip_bg)
                            setPadding(24, 12, 24, 12)
                            textSize = 13f
                            setOnClickListener {
                                // 一键复制
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("reply", reply)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@SocialActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                        }
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 12 }
                        replyContainer.addView(chip, params)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    replyContainer.removeAllViews()
                    val errorView = TextView(this@SocialActivity).apply {
                        text = "出错啦：${e.message}"
                        setTextColor(getColor(R.color.error))
                        textSize = 14f
                    }
                    replyContainer.addView(errorView)
                }
            }
        }
    }

    private fun parseReplies(content: String): List<String> {
        val map = linkedMapOf(
            "简洁版：" to "",
            "友好版：" to "",
            "专业版：" to ""
        )
        for (key in map.keys) {
            val start = content.indexOf(key)
            if (start != -1) {
                val sub = content.substring(start + key.length)
                // 找下一个版本标题或末尾
                val nextIdx = map.keys.map { sub.indexOf(it) }.filter { it != -1 }.minOrNull()
                val text = if (nextIdx != null) sub.substring(0, nextIdx) else sub
                map[key] = text.trim()
            }
        }
        return map.values.filter { it.isNotEmpty() }
    }
}
