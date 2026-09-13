package com.biometric.app.ai

import com.google.ai.client.generativeai.GenerativeModel
import java.util.*

class GeminiService(private val apiKey: String) {

    private val modelNames = listOf("gemini-1.5-flash-latest", "gemini-1.5-flash", "gemini-pro")

    suspend fun generateResponse(
        userQuery: String,
        intent: AiIntent,
        bundle: WorkforceDataBundle,
        insights: List<BusinessInsight>
    ): String {
        val isKeyPlaceholder = apiKey.isBlank() || apiKey.contains("YOUR_API_KEY")
        if (isKeyPlaceholder) return generateFallbackResponse(intent, bundle, insights)

        val prompt = buildPrompt(userQuery, intent, bundle, insights)
        
        for (modelName in modelNames) {
            try {
                val model = GenerativeModel(modelName = modelName, apiKey = apiKey)
                val response = model.generateContent(prompt)
                if (response.text != null) return response.text!!
            } catch (_: Exception) {}
        }

        return generateFallbackResponse(intent, bundle, insights)
    }

    private fun buildPrompt(
        query: String,
        intent: AiIntent,
        bundle: WorkforceDataBundle,
        insights: List<BusinessInsight>
    ): String {
        val contextStr = """
            Workforce Context for ${bundle.periodLabel}:
            - Total Employees: ${bundle.employees.size}
            - Active Attendance Logs: ${bundle.attendance.size}
            - Pending Regularizations: ${bundle.regularizations.count { it.status == "Pending" }}
            
            Key Insights:
            ${insights.joinToString("\n") { "- ${it.title}: ${it.description}" }}
        """.trimIndent()

        return """
            You are "Beast AI", a professional Workforce Intelligence Assistant for Biometric Payroll.
            Your goal is to help administrators understand staff attendance, puncutality, and payroll data.
            
            User Question: "$query"
            Detected Intent: $intent
            
            Context:
            $contextStr
            
            Rules:
            1. ONLY answer questions related to Biometric Payroll workforce, staff, attendance, and analytics.
            2. Be conversational and helpful.
            3. Use bullet points for readability.
            
            Generate the response in natural language:
        """.trimIndent()
    }

    private fun generateFallbackResponse(
        intent: AiIntent,
        bundle: WorkforceDataBundle,
        insights: List<BusinessInsight>
    ): String {
        val sb = StringBuilder()
        val period = bundle.periodLabel

        sb.append("Workforce Intelligence Summary for **$period**: 👤\n\n")
        sb.append("• **Active Staff**: ${bundle.employees.size}\n")
        sb.append("• **Attendance Logs**: ${bundle.attendance.size}\n\n")

        if (insights.isNotEmpty()) {
            sb.append("**Key Observations:**\n")
            insights.forEach { insight ->
                val emoji = when(insight.impact) {
                    ImpactLevel.POSITIVE -> "✅"
                    ImpactLevel.NEGATIVE -> "⚠️"
                    else -> "ℹ️"
                }
                sb.append("• $emoji **${insight.title}**: ${insight.description}\n")
                insight.recommendation?.let { sb.append("  - 💡 _Recommendation_: $it\n") }
            }
        } else {
            sb.append("Everything looks stable for this period. No critical attendance risks detected.")
        }

        return sb.toString()
    }
}
