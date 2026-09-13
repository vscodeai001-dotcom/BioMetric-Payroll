package com.biometric.app.util

import java.util.Locale

object StringUtil {

    /**
     * Converts a string to Title Case (First letter of every word capitalized).
     * Example: "TEA SHOP pos" -> "Tea Shop Pos"
     */
    fun toTitleCase(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        return input.split(" ")
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { word ->
                word.lowercase(Locale.getDefault())
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            .joinToString(" ")
    }
}
