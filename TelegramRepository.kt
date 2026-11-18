package com.tiff.screenassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TelegramContact(
    val name: String,
    val chatId: String
)

class TelegramRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tiff_telegram", Context.MODE_PRIVATE)

    var autoTapSendInApp: Boolean
        get() = prefs.getBoolean("auto_tap_send_in_app", true)
        set(value) = prefs.edit().putBoolean("auto_tap_send_in_app", value).apply()

    var botToken: String
        get() = prefs.getString("bot_token", "") ?: ""
        set(value) = prefs.edit().putString("bot_token", value.trim()).apply()

    var defaultChatId: String
        get() = prefs.getString("default_chat_id", "") ?: ""
        set(value) = prefs.edit().putString("default_chat_id", value.trim()).apply()

    var pollingEnabled: Boolean
        get() = prefs.getBoolean("polling_enabled", true)
        set(value) = prefs.edit().putBoolean("polling_enabled", value).apply()

    var lastUpdateOffset: Long
        get() = prefs.getLong("last_update_offset", 0L)
        set(value) = prefs.edit().putLong("last_update_offset", value).apply()

    fun isConfigured(): Boolean = botToken.isNotBlank() && defaultChatId.isNotBlank()

    fun getContacts(): List<TelegramContact> {
        val raw = prefs.getString("contacts_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<TelegramContact>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                TelegramContact(
                    name = obj.optString("name", ""),
                    chatId = obj.optString("chatId", "")
                )
            )
        }
        return list
    }

    fun addContact(name: String, chatId: String) {
        val cleanName = name.trim()
        val cleanId = chatId.trim()
        if (cleanName.isEmpty() || cleanId.isEmpty()) return
        val list = getContacts().filterNot { it.name.equals(cleanName, ignoreCase = true) }.toMutableList()
        list.add(0, TelegramContact(cleanName, cleanId))
        saveContacts(list)
    }

    fun deleteContact(name: String) {
        val list = getContacts().filterNot { it.name.equals(name, ignoreCase = true) }
        saveContacts(list)
    }

    fun resolveChatIdByName(nameQuery: String): String? {
        val q = nameQuery.trim().lowercase()
        if (q == "me" || q == "myself" || q == "saved") {
            return defaultChatId.ifBlank { null }
        }
        return getContacts().firstOrNull {
            it.name.lowercase() == q || it.name.lowercase().contains(q)
        }?.chatId
    }

    private fun saveContacts(list: List<TelegramContact>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("chatId", c.chatId)
            })
        }
        prefs.edit().putString("contacts_json", arr.toString()).apply()
    }
}
