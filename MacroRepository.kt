package com.tiff.screenassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MacroActionType(val label: String) {
    TELEGRAM_APP_DRIVER("Telegram App: Personal Auto-Driver"),
    TELEGRAM_BOT_MESSAGE("Telegram Bot: Send Bot Message"),
    GMAIL_COMPOSE("Gmail: Draft Email"),
    CALENDAR_EVENT("Calendar: Create Event"),
    PHONE_CALL("Phone: Place Call / Dial"),
    SCREEN_DRIVE("Screen: AI Drive Task")
}

data class VoiceMacro(
    val id: String = UUID.randomUUID().toString(),
    val triggerPhrase: String,
    val actionType: MacroActionType,
    val param1: String,
    val param2: String = "",
    val param3: String = ""
)

class MacroRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tiff_macros", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("macros_json")) {
            seedDefaults()
        }
    }

    private fun seedDefaults() {
        val defaults = listOf(
            VoiceMacro(
                triggerPhrase = "note to self on telegram",
                actionType = MacroActionType.TELEGRAM_APP_DRIVER,
                param1 = "Saved Messages",
                param2 = "Quick voice note from Tiff!"
            ),
            VoiceMacro(
                triggerPhrase = "draft client invoice",
                actionType = MacroActionType.GMAIL_COMPOSE,
                param1 = "billing@client.com",
                param2 = "Invoice for Services Rendered",
                param3 = "Hi there,\n\nPlease find attached our latest invoice. Thanks so much!"
            ),
            VoiceMacro(
                triggerPhrase = "schedule team sync",
                actionType = MacroActionType.CALENDAR_EVENT,
                param1 = "Team Sync",
                param2 = "Google Meet",
                param3 = "Weekly progress check-in"
            ),
            VoiceMacro(
                triggerPhrase = "call office",
                actionType = MacroActionType.PHONE_CALL,
                param1 = "+15550199"
            )
        )
        saveAll(defaults)
    }

    fun getAll(): List<VoiceMacro> {
        val raw = prefs.getString("macros_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = mutableListOf<VoiceMacro>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val typeName = obj.optString("actionType", MacroActionType.SCREEN_DRIVE.name)
            val type = try {
                MacroActionType.valueOf(typeName)
            } catch (e: Exception) {
                MacroActionType.SCREEN_DRIVE
            }
            result.add(
                VoiceMacro(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    triggerPhrase = obj.optString("triggerPhrase", ""),
                    actionType = type,
                    param1 = obj.optString("param1", ""),
                    param2 = obj.optString("param2", ""),
                    param3 = obj.optString("param3", "")
                )
            )
        }
        return result
    }

    fun addMacro(macro: VoiceMacro) {
        val list = getAll().toMutableList()
        list.add(0, macro)
        saveAll(list)
    }

    fun deleteMacro(id: String) {
        val list = getAll().filterNot { it.id == id }
        saveAll(list)
    }

    fun findMatchingMacro(spokenText: String): VoiceMacro? {
        val normalized = spokenText.lowercase().trim()
        return getAll().firstOrNull { macro ->
            val trigger = macro.triggerPhrase.lowercase().trim()
            trigger.isNotEmpty() && (normalized == trigger || normalized.contains(trigger))
        }
    }

    private fun saveAll(macros: List<VoiceMacro>) {
        val arr = JSONArray()
        for (m in macros) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("triggerPhrase", m.triggerPhrase)
                put("actionType", m.actionType.name)
                put("param1", m.param1)
                put("param2", m.param2)
                put("param3", m.param3)
            })
        }
        prefs.edit().putString("macros_json", arr.toString()).apply()
    }
}
