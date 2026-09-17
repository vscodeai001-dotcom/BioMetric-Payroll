package com.biometric.app.domain.attendance

import com.biometric.app.data.MobileSessionStore
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Realtime mirror of the Web attendance feature hierarchy.
 *
 * This class only resolves configuration. It deliberately does not calculate
 * payroll or attendance outcomes. Automatic geofence punch reconciliation stays
 * on the existing Web compatibility/attendance engine.
 */
@Singleton
class AttendancePolicyRepository @Inject constructor(
    private val sessionStore: MobileSessionStore
) {
    data class Policy(
        val geoFencingEnabled: Boolean = true,
        val dualAttendanceEnabled: Boolean = false,
        val automaticGeofencePunchingEnabled: Boolean = false,
        val officeLatitude: Double = 0.0,
        val officeLongitude: Double = 0.0,
        val geoRadiusMeters: Int = 0
    ) {
        fun normalized(): Policy {
            // Exact Web hierarchy: Geo-Fencing is the master switch. Neither
            // Dual Attendance nor Automatic Geofence Punching may remain active
            // when Geo-Fencing is disabled.
            return if (!geoFencingEnabled) copy(
                dualAttendanceEnabled = false,
                automaticGeofencePunchingEnabled = false
            ) else this
        }
    }

    private fun ownerRef(): DatabaseReference? {
        val owner = sessionStore.firebaseOwnerUid()?.takeIf { it.isNotBlank() } ?: return null
        return com.google.firebase.database.FirebaseDatabase.getInstance()
            .reference.child("owners").child(owner)
    }

    fun observe(): Flow<Policy> = callbackFlow {
        val owner = ownerRef()
        if (owner == null) {
            trySend(Policy())
            close()
            return@callbackFlow
        }

        val featureRef = owner.child("feature_settings").child("1")
        val companyRef = owner.child("company_settings").child("1")
        var featureSnapshot: DataSnapshot? = null
        var companySnapshot: DataSnapshot? = null

        fun emitPolicy() {
            val f = featureSnapshot
            val c = companySnapshot
            if (f == null && c == null) return
            trySend(buildPolicy(f, c).normalized())
        }

        val featureListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                featureSnapshot = snapshot
                emitPolicy()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        val companyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                companySnapshot = snapshot
                emitPolicy()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }

        featureRef.addValueEventListener(featureListener)
        companyRef.addValueEventListener(companyListener)
        awaitClose {
            featureRef.removeEventListener(featureListener)
            companyRef.removeEventListener(companyListener)
        }
    }.distinctUntilChanged()

    suspend fun read(): Policy {
        val owner = ownerRef() ?: return Policy()
        val feature = owner.child("feature_settings").child("1").get().await()
        val company = owner.child("company_settings").child("1").get().await()
        return buildPolicy(feature, company).normalized()
    }

    private fun buildPolicy(feature: DataSnapshot?, company: DataSnapshot?): Policy {
        fun bool(snapshot: DataSnapshot?, vararg keys: String): Boolean? {
            for (key in keys) {
                snapshot?.child(key)?.value?.let { value ->
                    when (value) {
                        is Boolean -> return value
                        is Number -> return value.toInt() != 0
                        is String -> return value.toBooleanStrictOrNull()
                    }
                }
            }
            return null
        }
        fun double(snapshot: DataSnapshot?, vararg keys: String): Double {
            for (key in keys) {
                snapshot?.child(key)?.value?.toString()?.toDoubleOrNull()?.let { return it }
            }
            return 0.0
        }
        fun int(snapshot: DataSnapshot?, vararg keys: String): Int {
            for (key in keys) {
                snapshot?.child(key)?.value?.toString()?.toIntOrNull()?.let { return it }
                snapshot?.child(key)?.value?.toString()?.toDoubleOrNull()?.toInt()?.let { return it }
            }
            return 0
        }

        return Policy(
            geoFencingEnabled = bool(feature, "enableGeoFencing", "enable_geo_fencing", "EnableGeoFencing") ?: true,
            dualAttendanceEnabled = bool(feature, "enableDualAttendance", "enable_dual_attendance", "EnableDualAttendance") ?: false,
            automaticGeofencePunchingEnabled = bool(feature, "enableAutomaticGeofencePunching", "enable_automatic_geofence_punching", "EnableAutomaticGeofencePunching") ?: false,
            officeLatitude = double(company, "geoLatitude", "officeLatitude", "latitude", "GeoLatitude", "OfficeLatitude"),
            officeLongitude = double(company, "geoLongitude", "officeLongitude", "longitude", "GeoLongitude", "OfficeLongitude"),
            geoRadiusMeters = int(company, "geoRadiusMeters", "geoRadius", "radius", "GeoRadiusMeters", "GeoRadius")
        )
    }
}
