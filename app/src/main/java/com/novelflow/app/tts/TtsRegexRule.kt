package com.novelflow.app.tts

import org.json.JSONObject
import java.util.UUID

data class TtsRegexRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "새 규칙",
    val pattern: String,
    val replacement: String = "",
    val ignoreCase: Boolean = true,
    val isRegex: Boolean = true,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("term", pattern)
        put("replacement", replacement)
        put("ignoreCase", ignoreCase)
        put("isRegex", isRegex)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(obj: JSONObject): TtsRegexRule {
            return TtsRegexRule(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name").ifBlank { obj.optString("term", "규칙") },
                pattern = obj.optString("term").ifBlank { obj.optString("word", "") },
                replacement = obj.optString("replacement").ifBlank {
                    obj.optString("pronunciation", obj.optString("ipa", ""))
                },
                ignoreCase = obj.optBoolean("ignoreCase", true),
                isRegex = obj.optBoolean("isRegex", true),
                enabled = obj.optBoolean("enabled", true),
            )
        }
    }
}
