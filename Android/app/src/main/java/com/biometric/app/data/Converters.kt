package com.biometric.app.data

import androidx.room.TypeConverter
import com.biometric.app.data.entity.SalaryRules
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromSalaryRules(rules: SalaryRules?): String? = gson.toJson(rules)

    @TypeConverter
    fun toSalaryRules(json: String?): SalaryRules? = gson.fromJson(json, SalaryRules::class.java)

    @TypeConverter
    fun fromStringMap(map: Map<String, Boolean>?): String? = gson.toJson(map)

    @TypeConverter
    fun toStringMap(json: String?): Map<String, Boolean>? {
        val type = object : TypeToken<Map<String, Boolean>>() {}.type
        return gson.fromJson(json, type)
    }

    @TypeConverter
    fun toStringList(json: String?): List<String>? {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }

    @TypeConverter
    fun fromStringList(list: List<String>?): String? = gson.toJson(list)
}
