package com.tiff.screenassistant.data

import android.content.Context

enum class SoulPreset(val displayName: String, val template: String) {
    CHIEF_OF_STAFF(
        "Sharp Chief of Staff & Best Friend",
        """# SOUL.md — Tiff
You are Tiff, a sharp, warm, and witty personal AI companion living on the user's phone.
- Vibe: Confident, perceptive, loyal best friend meets elite chief of staff. Never sound like a stiff robot or Victorian butler.
- Speech: Keep spoken lines punchy (1 short sentence), natural, and warm. Use the user's nickname naturally when appropriate.
- Initiative: Notice context across screens, anticipate what helps, and protect the user's privacy and security fiercely.""".trimIndent()
    ),
    CALM_COACH(
        "Calm & Encouraging Tech Coach",
        """# SOUL.md — Tiff
You are Tiff, a patient, reassuring, and crystal-clear personal guide.
- Vibe: Warm, steady, and supportive—never make the user feel rushed or overwhelmed by tech menus.
- Speech: Speak clearly in one encouraging, concise sentence per step.""".trimIndent()
    ),
    DIRECT_PRO(
        "Ultra-Direct & Zero-Fluff",
        """# SOUL.md — Tiff
You are Tiff, an ultra-fast, concise screen operator.
- Vibe: Minimalist, sharp, high-signal, zero filler words.
- Speech: 3 to 6 words max per update.""".trimIndent()
    ),
    CUSTOM(
        "Custom Soul (Write Your Own)",
        ""
    )
}

class SoulRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tiff_soul", Context.MODE_PRIVATE)

    var userNickname: String
        get() = prefs.getString("user_nickname", "Boss") ?: "Boss"
        set(value) = prefs.edit().putString("user_nickname", value.trim().ifBlank { "Boss" }).apply()

    var preset: SoulPreset
        get() {
            val name = prefs.getString("soul_preset", SoulPreset.CHIEF_OF_STAFF.name)
                ?: SoulPreset.CHIEF_OF_STAFF.name
            return try {
                SoulPreset.valueOf(name)
            } catch (e: Exception) {
                SoulPreset.CHIEF_OF_STAFF
            }
        }
        set(value) = prefs.edit().putString("soul_preset", value.name).apply()

    var soulDocument: String
        get() = prefs.getString("soul_doc", SoulPreset.CHIEF_OF_STAFF.template)
            ?: SoulPreset.CHIEF_OF_STAFF.template
        set(value) = prefs.edit().putString("soul_doc", value.trim()).apply()

    fun buildSystemSoulBlock(): String {
        return """
            $soulDocument
            User's preferred name/nickname: "$userNickname".
        """.trimIndent()
    }
}
