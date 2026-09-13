package com.biometric.app.data

object FinancialCategory {
    const val SALARY = "SALARY"
    const val ADVANCE = "ADVANCE"
    const val OTHER = "OTHER"

    fun matches(input: String?, target: String): Boolean {
        if (input == null) return false
        val normalized = input.trim().uppercase()
        val targetNormalized = target.trim().uppercase()
        return (normalized == targetNormalized)
    }

    fun isSalary(category: String?, description: String? = null): Boolean {
        if (category == null) return false
        val cat = category.trim().uppercase()
        val desc = description?.trim()?.uppercase() ?: ""
        return cat.contains("SALARY") || desc.contains("SALARY")
    }

    fun isAdvance(category: String?): Boolean {
        if (category == null) return false
        return category.trim().uppercase().contains("ADVANCE")
    }

    fun getEmoji(category: String, description: String? = null): String {
        val normalized = category.trim().uppercase()
        val desc = description?.trim()?.uppercase() ?: ""

        return when {
            isSalary(normalized, desc) -> "🧑‍💼"
            isAdvance(normalized) -> "💸"
            else -> "₹"
        }
    }
}
