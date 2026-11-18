package com.tiff.screenassistant.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import com.tiff.screenassistant.data.PrefsManager
import com.tiff.screenassistant.data.VoiceEngineMode
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class TiffVoiceManager(
    private val context: Context,
    private val prefs: PrefsManager,
    private val onVoicesLoaded: ((List<Voice>) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.US
            val femaleVoices = getAvailableFemaleVoices()
            applySelectedLocalVoice(femaleVoices)
            onVoicesLoaded?.invoke(femaleVoices)
        }
    }

    fun getAvailableFemaleVoices(): List<Voice> {
        if (!ttsReady) return emptyList()
        val allVoices = tts?.voices ?: return emptyList()
        val englishVoices = allVoices.filter { it.locale.language.equals("en", ignoreCase = true) }

        val femaleHints = listOf(
            "female", "sfg", "tpf", "gba", "iol", "samantha",
            "victoria", "karen", "moira", "tessa", "aria", "jenny", "zira"
        )
        val filtered = englishVoices.filter { voice ->
            val lower = voice.name.lowercase(Locale.ROOT)
            femaleHints.any { hint -> lower.contains(hint) } && !lower.contains("male_")
        }.sortedBy { it.name }

        return filtered.ifEmpty { englishVoices.sortedBy { it.name } }
    }

    private fun applySelectedLocalVoice(femaleVoices: List<Voice>) {
        val savedName = prefs.selectedFemaleVoiceName
        val chosen = femaleVoices.firstOrNull { it.name == savedName } ?: femaleVoices.firstOrNull()
        if (chosen != null) {
            tts?.voice = chosen
        }
        tts?.setPitch(1.06f)
        tts?.setSpeechRate(1.02f)
    }

    fun speak(line: String) {
        if (prefs.quietMode || line.isBlank()) return
        stop()

        if (prefs.voiceEngineMode == VoiceEngineMode.GEMINI_NEURAL_TTS && prefs.apiKey.isNotBlank()) {
            scope.launch {
                val playedNeural = trySpeakWithGeminiTts(line)
                if (!playedNeural) {
                    speakLocal(line)
                }
            }
        } else {
            speakLocal(line)
        }
    }

    private fun speakLocal(line: String) {
        if (!ttsReady) return
        applySelectedLocalVoice(getAvailableFemaleVoices())
        tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "tiff_utterance")
    }

    private suspend fun trySpeakWithGeminiTts(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val apiKey = prefs.apiKey
            val model = "gemini-3.8-flash-lite-tts"
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            val style = prefs.geminiVoiceStyle
            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", "Voice style: $style")))
                })
            }

            val req = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val respStr = resp.body?.string() ?: return@withContext false
                val root = JSONObject(respStr)
                val inlineData = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getJSONObject("inlineData")

                val b64Audio = inlineData.getString("data")
                val audioBytes = Base64.decode(b64Audio, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "tiff_tts_clip.wav")
                tempFile.writeBytes(audioBytes)

                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        prepare()
                        start()
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        tts?.stop()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
        tts?.shutdown()
    }
}
