package com.example.lingban

data class Message(
    val text: String,
    val isUser: Boolean,
    val id: Long = 0   // 对应数据库里的 id，删除时使用
)
