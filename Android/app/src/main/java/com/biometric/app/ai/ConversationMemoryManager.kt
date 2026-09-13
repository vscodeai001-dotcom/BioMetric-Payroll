package com.biometric.app.ai

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson

class ConversationMemoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Memory(
        var lastShopId: String? = null,
        var lastStart: Long? = null,
        var lastEnd: Long? = null,
        var lastPeriodLabel: String? = null,
        var lastEntities: List<String> = emptyList(),
        var lastIntent: AiIntent? = null,
    )

    private var currentMemory: Memory = loadMemory()

    fun update(
        shopId: String? = null,
        start: Long? = null,
        end: Long? = null,
        periodLabel: String? = null,
        entities: List<String>? = null,
        intent: AiIntent? = null
    ) {
        shopId?.let { currentMemory.lastShopId = it }
        start?.let { currentMemory.lastStart = it }
        end?.let { currentMemory.lastEnd = it }
        periodLabel?.let { currentMemory.lastPeriodLabel = it }
        if (!entities.isNullOrEmpty()) currentMemory.lastEntities = entities
        intent?.let { currentMemory.lastIntent = it }
        saveMemory()
    }

    fun getMemory(): Memory = currentMemory

    private fun loadMemory(): Memory {
        val json = prefs.getString("memory", null)
        return if (json != null) {
            gson.fromJson(json, Memory::class.java)
        } else {
            Memory()
        }
    }

    private fun saveMemory() {
        prefs.edit {
            putString("memory", gson.toJson(currentMemory))
        }
    }

    fun clear() {
        currentMemory = Memory()
        prefs.edit {
            remove("memory")
        }
    }
}
