package com.waryway.gab.tools

/**
 * OpenAI-compatible tool definition passed to Gab AI chat/completions.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
) {
    fun toOpenAiToolJson(): String =
        """{"type":"function","function":{"name":"$name","description":${jsonString(description)},"parameters":$parametersJson}}"""

    companion object {
        fun jsonString(value: String): String =
            "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\""
    }
}