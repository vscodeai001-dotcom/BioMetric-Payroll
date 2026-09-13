package com.biometric.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.biometric.app.ai.*
import com.biometric.app.data.MainRepository
import com.biometric.app.util.DateRangeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val chartData: List<Entry>? = null,
    val chartLabel: String? = null,
    val actionText: String? = null,
    val actionType: String? = null
)

@HiltViewModel
class AiChatViewModel @Inject constructor(
    application: Application,
    private val repository: MainRepository,
    private val geminiService: GeminiService,
) : AndroidViewModel(application) {

    private val gson = Gson()
    private val prefs = application.getSharedPreferences("ai_chat_prefs", Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow(loadHistory())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(value = false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private var currentShopId: String? = null

    fun setShopId(shopId: String?) {
        currentShopId = if (shopId.isNullOrEmpty()) null else shopId
        generateInitialSuggestions()
    }

    private fun loadHistory(): List<ChatMessage> {
        val json = prefs.getString("history", null)
        return if (json != null) {
            try {
                gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type)
            } catch (_: Exception) {
                listOf(getDefaultWelcome())
            }
        } else {
            listOf(getDefaultWelcome())
        }
    }

    private fun getDefaultWelcome() = ChatMessage("Hello! I'm **Beast AI** 🦁, your personal workforce assistant. I can analyze staff attendance, regularity, and payroll status. Ask me anything about your team.", false)

    private fun saveHistory(messages: List<ChatMessage>) {
        viewModelScope.launch(Dispatchers.IO) {
            val lastFifty = if (messages.size > 50) messages.takeLast(50) else messages
            prefs.edit { putString("history", gson.toJson(lastFifty)) }
        }
    }

    fun clearHistory() {
        _messages.value = listOf(getDefaultWelcome())
        prefs.edit { remove("history") }
    }

    private fun generateInitialSuggestions() {
        _suggestions.value = listOf("How is staff attendance today?", "Who is most absent this week?", "Any pending approvals?", "Show workforce summary")
    }

    fun sendMessage(query: String) {
        if (query.isBlank()) return
        val userMsg = ChatMessage(query, true)
        _messages.update { (it + userMsg).takeLast(60) }

        viewModelScope.launch {
            _isLoading.value = true
            processQuery(query)
            _isLoading.value = false
            saveHistory(_messages.value)
        }
    }

    private suspend fun processQuery(query: String) {
        val range = DateRangeUtil.getRangeForPeriod("Monthly", System.currentTimeMillis(), true)
        val bundle = BusinessContextBuilder(repository).build(currentShopId, range.first, range.second, "This Month")
        
        // Simplified intent detection for workforce
        val intent = when {
            query.contains("attendance", true) || query.contains("present", true) -> AiIntent.ATTENDANCE_QUERY
            query.contains("salary", true) || query.contains("payroll", true) -> AiIntent.SALARY_QUERY
            query.contains("leave", true) -> AiIntent.LEAVE_QUERY
            else -> AiIntent.GENERAL
        }

        val insights = BusinessInsightEngine.analyze(intent, bundle)
        val response = geminiService.generateResponse(query, intent, bundle, insights)
        
        val message = ChatMessage(text = response, isUser = false)
        _messages.update { (it + message).takeLast(60) }
    }
}
