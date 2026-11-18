package com.tiff.screenassistant.data

import android.content.Context

enum class AiProvider(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini (Default)", "gemini-3.8-flash"),
    ANTHROPIC("Anthropic Claude", "claude-sonnet-5-5"),
    CUSTOM("Custom Endpoint (OpenAI-Compatible)", "gpt-4o")
}

enum class VoiceEngineMode(val displayName: String) {
    ANDROID_FEMALE("Android Local Female Voice (Offline & Instant)"),
    GEMINI_NEURAL_TTS("Gemini 3.8 Neural Female Voice (API Key Required)")
}

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("tiff_prefs", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() {
            val name = prefs.getString("provider", AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
            return try {
                AiProvider.valueOf(name)
            } catch (e: Exception) {
                AiProvider.GEMINI
            }
        }
        set(value) = prefs.edit().putString("provider", value.name).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var modelId: String
        get() = prefs.getString("model_id", provider.defaultModel) ?: provider.defaultModel
        set(value) = prefs.edit().putString("model_id", value.trim()).apply()

    var customEndpoint: String
        get() = prefs.getString("custom_endpoint", "https://api.openai.com/v1/chat/completions")
            ?: "https://api.openai.com/v1/chat/completions"
        set(value) = prefs.edit().putString("custom_endpoint", value.trim()).apply()

    var voiceEngineMode: VoiceEngineMode
        get() {
            val name = prefs.getString("voice_engine_mode", VoiceEngineMode.ANDROID_FEMALE.name)
                ?: VoiceEngineMode.ANDROID_FEMALE.name
            return try {
                VoiceEngineMode.valueOf(name)
            } catch (e: Exception) {
                VoiceEngineMode.ANDROID_FEMALE
            }
        }
        set(value) = prefs.edit().putString("voice_engine_mode", value.name).apply()

    var selectedFemaleVoiceName: String
        get() = prefs.getString("female_voice_name", "") ?: ""
        set(value) = prefs.edit().putString("female_voice_name", value).apply()

    var geminiVoiceStyle: String
        get() = prefs.getString(
            "gemini_voice_style",
            "A warm, articulate young woman with a natural, friendly, and confident cadence."
        ) ?: "A warm, articulate young woman with a natural, friendly, and confident cadence."
        set(value) = prefs.edit().putString("gemini_voice_style", value.trim()).apply()

    var quietMode: Boolean
        get() = prefs.getBoolean("quiet_mode", false)
        set(value) = prefs.edit().putBoolean("quiet_mode", value).apply()
}
