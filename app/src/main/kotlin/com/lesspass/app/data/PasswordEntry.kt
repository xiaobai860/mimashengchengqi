package com.lesspass.app.data

import java.util.UUID

/**
 * 密码本/KDBX 中的一个密码条目。
 */
data class PasswordEntry(
    val uuid: UUID = UUID.randomUUID(),
    val title: String,
    val username: String,
    val password: String,
    val masterPassword: String = "",
    val url: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
