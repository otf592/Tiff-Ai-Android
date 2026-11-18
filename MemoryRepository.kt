package com.tiff.screenassistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MemoryCategory(val label: String) {
    USER_FACT("Personal Fact / Contact"),
    PREFERENCE("Style & App Preference"),
    APP_HABIT("App Navigation Habit")
}

data class MemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val fact: String,
    val timestamp: String = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
)

class MemoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tiff_memory", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("long_term_json")) {
            seedStarterMemories()
        }
    }

    private fun seedStarterMemories() {
        val starter = listOf(
            MemoryItem(
                category = MemoryCategory.PREFERENCE,
                fact = "Always keep spoken updates concise, warm, and human."
            )
        )
        saveLongTerm(starter)
    }

    fun getAllLongTerm(): List<MemoryItem> {
        val raw = prefs.getString("long_term_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<MemoryItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val catName = obj.optString("category", MemoryCategory.USER_FACT.name)
            val cat = try {
                MemoryCategory.valueOf(catName)
            } catch (e: Exception) {
                MemoryCategory.USER_FACT
            }
            out.add(
                MemoryItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    category = cat,
                    fact = obj.optString("fact", ""),
                    timestamp = obj.optString("timestamp", "")
                )
            )
        }
        return out
    }

    fun addLongTermMemory(fact: String, category: MemoryCategory = MemoryCategory.USER_FACT) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        val list = getAllLongTerm().toMutableList()
        list.add(0, MemoryItem(category = category, fact = clean))
        saveLongTerm(list)
    }

    fun deleteLongTermMemory(id: String) {
        val list = getAllLongTerm().filterNot { it.id == id }
        saveLongTerm(list)
    }

    fun recordScreenEvent(summary: String) {
        val raw = prefs.getString("recent_events_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        val stamped = "[${SimpleDateFormat("HH:mm", Locale.US).format(Date())}] $summary"
        list.add(0, stamped)
        val trimmed = list.take(5)
        val newArr = JSONArray()
        trimmed.forEach { newArr.put(it) }
        prefs.edit().putString("recent_events_json", newArr.toString()).apply()
    }

    fun getRecentScreenEvents(): List<String> {
        val raw = prefs.getString("recent_events_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.getString(i))
        }
        return out
    }

    fun buildMemoryPromptContext(): String {
        val longTerm = getAllLongTerm()
        val recent = getRecentScreenEvents()
        val sb = StringBuilder()
        if (longTerm.isNotEmpty()) {
            sb.appendLine("PERSISTENT USER MEMORY:")
            longTerm.take(20).forEach {
                sb.appendLine("- [${it.category.label}] ${it.fact}")
            }
        }
        if (recent.isNotEmpty()) {
            sb.appendLine("RECENT SCREEN WORKING MEMORY:")
            recent.forEach {
                sb.appendLine("- $it")
            }
        }
        return sb.toString().trim()
    }

    private fun saveLongTerm(items: List<MemoryItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("category", item.category.name)
                put("fact", item.fact)
                put("timestamp", item.timestamp)
            })
        }
        prefs.edit().putString("long_term_json", arr.toString()).apply()
    }
}
