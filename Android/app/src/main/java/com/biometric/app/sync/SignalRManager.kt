package com.biometric.app.sync

import android.util.Log
import com.biometric.app.data.MobileSessionStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalRManager @Inject constructor(
    private val sessionStore: MobileSessionStore
) {
    private var hubConnection: HubConnection? = null
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var connectInProgress = false
    
    private val _dataChangeEvents = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 10)
    val dataChangeEvents = _dataChangeEvents.asSharedFlow()

    private val _liveLocations = MutableStateFlow<Map<Int, LiveLocation>>(emptyMap())
    val liveLocations = _liveLocations.asStateFlow()

    private val hubUrl = "https://biometric-payroll.onrender.com/hubs/attendance-refresh"

    @Synchronized
    fun start() {
        if (!sessionStore.isLoggedIn()) return
        
        if (hubConnection != null) {
             if (hubConnection?.connectionState == HubConnectionState.DISCONNECTED) {
                 connect()
             }
             return
        }

        managerScope.launch {
            try {
                hubConnection = HubConnectionBuilder.create(hubUrl)
                    .withAccessTokenProvider(Single.fromCallable { sessionStore.token() ?: "" })
                    .build()

                setupListeners()
                connect()
            } catch (e: Exception) {
                Log.e("SignalR", "Failed to build HubConnection", e)
            }
        }
    }

    private fun setupListeners() {
        val hub = hubConnection ?: return

        hub.on("GeoSettingsChanged", { data ->
            managerScope.launch {
                Log.d("SignalR", "GeoSettingsChanged received: $data")
                try {
                    val json = Gson().toJson(data)
                    val update = Gson().fromJson(json, GeoSettingsChangedEvent::class.java)
                    if (update != null && update.geoRadiusMeters > 0) {
                        _dataChangeEvents.emit(SyncEvent.GeoSettingsChanged(update))
                    }
                } catch (e: Exception) {
                    Log.e("SignalR", "Failed to parse GeoSettingsChanged", e)
                }
            }
        }, Any::class.java)

        hub.on("LocationChanged", { data ->
            managerScope.launch {
                Log.d("SignalR", "LocationChanged received: $data")
                try {
                    val json = Gson().toJson(data)
                    val update = Gson().fromJson(json, LiveLocation::class.java)
                    if (update != null && update.employeeId > 0) {
                        val current = _liveLocations.value.toMutableMap()
                        current[update.employeeId] = update
                        _liveLocations.value = current
                        _dataChangeEvents.emit(SyncEvent.LocationChanged)
                    }
                } catch (e: Exception) {
                    Log.e("SignalR", "Failed to parse LocationChanged", e)
                }
            }
        }, Any::class.java)

        hub.on("DataChanged", { _ ->
            Log.d("SignalR", "DataChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.DataChanged) }
        }, Any::class.java)

        hub.on("AttendanceChanged", { _ ->
            Log.d("SignalR", "AttendanceChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.AttendanceChanged) }
        }, Any::class.java)

        hub.on("PunchChanged", { _ ->
            Log.d("SignalR", "PunchChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.PunchChanged) }
        }, Any::class.java)

        hub.on("LeaveChanged", { _ ->
            Log.d("SignalR", "LeaveChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.LeaveChanged) }
        }, Any::class.java)

        hub.on("AdvanceChanged", { _ ->
            Log.d("SignalR", "AdvanceChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.AdvanceChanged) }
        }, Any::class.java)

        hub.on("BonusChanged", { _ ->
            Log.d("SignalR", "BonusChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.BonusChanged) }
        }, Any::class.java)

        hub.on("TaxDeclarationChanged", { _ ->
            Log.d("SignalR", "TaxDeclarationChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.TaxDeclarationChanged) }
        }, Any::class.java)

        hub.on("EmployeeChanged", { _ ->
            Log.d("SignalR", "EmployeeChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.EmployeeChanged) }
        }, Any::class.java)

        hub.on("SessionEnded", { data ->
            Log.d("SignalR", "SessionEnded received: $data")
            try {
                val json = Gson().toJson(data)
                val event = Gson().fromJson(json, SessionEndedEvent::class.java)
                
                managerScope.launch { 
                    _dataChangeEvents.emit(SyncEvent.SessionEnded(event?.employeeId ?: 0, event?.sessionId ?: "")) 
                }
            } catch (e: Exception) {
                Log.e("SignalR", "Failed to parse SessionEnded", e)
                managerScope.launch { _dataChangeEvents.emit(SyncEvent.SessionEnded(0, "")) }
            }
        }, Any::class.java)

        hub.on("GlobalRefresh", { _ ->
            Log.d("SignalR", "GlobalRefresh received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.GlobalRefresh) }
        }, Any::class.java)

        hub.on("ApplicationDataChanged", { data ->
            Log.d("SignalR", "ApplicationDataChanged received: $data")
            // ApplicationDataChanged is the central post-save invalidation signal.
            // Route it through the existing GlobalRefresh path so currently visible
            // Android screens refresh immediately without introducing a second
            // refresh architecture or changing business logic.
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.GlobalRefresh) }
        }, Any::class.java)

        hub.on("RegularizationChanged", { _ ->
            Log.d("SignalR", "RegularizationChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.RegularizationChanged) }
        }, Any::class.java)

        hub.on("ExitChanged", { _ ->
            Log.d("SignalR", "ExitChanged received")
            managerScope.launch { _dataChangeEvents.emit(SyncEvent.ExitChanged) }
        }, Any::class.java)

        hub.on("SessionStarted", { data ->
            Log.d("SignalR", "SessionStarted received: $data")
            managerScope.launch { 
                try {
                    val json = Gson().toJson(data)
                    val event = Gson().fromJson(json, SessionStartedEvent::class.java)
                    if (event != null) {
                        _dataChangeEvents.emit(SyncEvent.SessionStarted(event.employeeId, event.sessionId))
                    }
                } catch (e: Exception) {
                    Log.e("SignalR", "Failed to parse SessionStarted", e)
                }
            }
        }, Any::class.java)

        hub.onClosed { exception ->
            Log.w("SignalR", "Connection closed. Retrying in 5s...", exception)
            managerScope.launch {
                delay(5000)
                if (sessionStore.isLoggedIn()) connect()
            }
        }
    }

    private fun connect() {
        if (connectInProgress) return
        connectInProgress = true
        managerScope.launch {
            try {
                val hub = hubConnection ?: return@launch
                if (hub.connectionState == HubConnectionState.CONNECTED ||
                    hub.connectionState == HubConnectionState.CONNECTING) {
                    return@launch
                }
                hub.start()?.blockingAwait()
                Log.i("SignalR", "Successfully connected to Real-Time Hub ✅")
            } catch (e: Exception) {
                Log.e("SignalR", "Failed to connect to Hub: ${e.message}")
                managerScope.launch {
                    delay(10000)
                    if (sessionStore.isLoggedIn()) connect()
                }
            } finally {
                connectInProgress = false
            }
        }
    }

    fun stop() {
        hubConnection?.stop()
        hubConnection = null
    }

    data class SessionEndedEvent(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("sessionId") val sessionId: String?
    )

    sealed class SyncEvent {
        object DataChanged : SyncEvent()
        object AttendanceChanged : SyncEvent()
        object PunchChanged : SyncEvent()
        object LocationChanged : SyncEvent()
        data class GeoSettingsChanged(val settings: GeoSettingsChangedEvent) : SyncEvent()
        object LeaveChanged : SyncEvent()
        object AdvanceChanged : SyncEvent()
        object BonusChanged : SyncEvent()
        object TaxDeclarationChanged : SyncEvent()
        object EmployeeChanged : SyncEvent()
        object GlobalRefresh : SyncEvent()
        object ApplicationDataChanged : SyncEvent()
        object RegularizationChanged : SyncEvent()
        object ExitChanged : SyncEvent()
        data class SessionStarted(val employeeId: Int, val sessionId: String) : SyncEvent()
        data class SessionEnded(val employeeId: Int, val sessionId: String?) : SyncEvent()
    }

    data class SessionStartedEvent(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("sessionId") val sessionId: String
    )

    data class GeoSettingsChangedEvent(
        @SerializedName("officeLatitude") val officeLatitude: Double = 0.0,
        @SerializedName("officeLongitude") val officeLongitude: Double = 0.0,
        @SerializedName("geoRadiusMeters") val geoRadiusMeters: Int = 0
    )

    data class LiveLocation(
        @SerializedName("employeeId") val employeeId: Int,
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("accuracyMeters") val accuracyMeters: Double,
        @SerializedName("distanceMeters") val distanceMeters: Double,
        @SerializedName("allowedRadiusMeters") val allowedRadiusMeters: Int = 100,
        @SerializedName("isWithinAllowedRadius") val isWithinAllowedRadius: Boolean,
        @SerializedName("timestamp") val timestamp: String? = null,
        @SerializedName("speedMps") val speedMps: Double = 0.0,
        @SerializedName("movementState") val movementState: String = "Stopped"
    )
}
