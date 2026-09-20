package com.biometric.app.data.repository

import android.content.Context
import androidx.core.content.edit
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Firebase-authoritative tracking configuration with a local cache for service startup/offline use. */
@Singleton
class TrackingConfigurationRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    data class Config(
        val mode: String = "24/7",
        val customStart: String = "",
        val customEnd: String = "",
        val intervalSeconds: Int = 30, // Default: 30s, configurable 15s-3600s
        val enabled: Boolean = true
    )

    fun startRealtimeListener(onChanged: ((Config) -> Unit)? = null): ValueEventListener? {
        val owner = firebaseSync.getOwnerRef() ?: return null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val config = parse(snapshot)
                cache(config)
                onChanged?.invoke(config)
            }
            override fun onCancelled(error: DatabaseError) { }
        }
        owner.child(PATH).addValueEventListener(listener)
        return listener
    }

    fun removeRealtimeListener(listener: ValueEventListener?) {
        if (listener == null) return
        firebaseSync.getOwnerRef()?.child(PATH)?.removeEventListener(listener)
    }

    suspend fun load(): Config {
        val snapshot = firebaseSync.getOwnerRef()?.child(PATH)?.get()?.await()
        val config = snapshot?.let { parse(it) } ?: cached()
        cache(config)
        return config
    }

    suspend fun save(config: Config) {
        val owner = firebaseSync.getOwnerRef() ?: error("Firebase owner session is unavailable")
        val normalized = normalize(config)
        owner.child(PATH).setValue(mapOf(
            "mode" to normalized.mode,
            "customStart" to normalized.customStart,
            "customEnd" to normalized.customEnd,
            "intervalSeconds" to normalized.intervalSeconds,
            "enabled" to normalized.enabled,
            "updatedBy" to (FirebaseAuth.getInstance().currentUser?.uid ?: "unknown"),
            "updatedAt" to System.currentTimeMillis()
        )).await()
        cache(normalized)
        firebaseSync.notifyRealtimeAfterWrite("TrackingConfiguration", "MODIFIED")
    }

    fun cached(): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            mode = p.getString("tracking_mode", "24/7") ?: "24/7",
            customStart = p.getString("tracking_custom_start", "") ?: "",
            customEnd = p.getString("tracking_custom_end", "") ?: "",
            intervalSeconds = p.getInt("tracking_interval_seconds", 30).coerceIn(15, 3600),
            enabled = p.getBoolean("tracking_enabled", true)
        )
    }

    private fun cache(config: Config) {
        val c = normalize(config)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("tracking_mode", c.mode)
            putString("tracking_custom_start", c.customStart)
            putString("tracking_custom_end", c.customEnd)
            putInt("tracking_interval_seconds", c.intervalSeconds)
            putBoolean("tracking_enabled", c.enabled)
        }
    }

    private fun parse(s: DataSnapshot): Config = normalize(Config(
        mode = s.child("mode").getValue(String::class.java) ?: "24/7",
        customStart = s.child("customStart").getValue(String::class.java) ?: "",
        customEnd = s.child("customEnd").getValue(String::class.java) ?: "",
        intervalSeconds = (s.child("intervalSeconds").getValue(Int::class.java) ?: 30),
        enabled = s.child("enabled").getValue(Boolean::class.java) ?: true
    ))

    private fun normalize(c: Config): Config {
        val mode = c.mode.trim().uppercase().let { if (it == "SHIFT" || it == "CUSTOM" || it == "24/7") it else "24/7" }
        return c.copy(mode = mode, intervalSeconds = c.intervalSeconds.coerceIn(15, 3600))
    }

    companion object {
        const val PATH = "tracking_configuration"
        const val PREFS = "tracking_prefs"
    }
}
