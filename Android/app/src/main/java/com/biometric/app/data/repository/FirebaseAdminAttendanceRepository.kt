package com.biometric.app.data.repository

import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Admin attendance safety boundary.
 * Mirrors Web PayrollLockService: a PayrollHistory row for the employee's
 * year/month means the month is finalized and punch mutations are forbidden.
 */
@Singleton
class FirebaseAdminAttendanceRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    private fun ownerRef(): DatabaseReference =
        firebaseSync.getOwnerRef() ?: throw IllegalStateException("Firebase admin session is not initialized")

    suspend fun isPayrollLocked(employeeId: Int, epochMillis: Long): Boolean {
        if (employeeId <= 0 || epochMillis <= 0L) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val snapshot = ownerRef().child("payroll_history").get().await()
        return snapshot.children.any { row ->
            row.intAny("employeeId", "EmployeeID") == employeeId &&
                row.intAny("payYear", "PayYear") == year &&
                row.intAny("payMonth", "PayMonth") == month
        }
    }

    private fun DataSnapshot.intAny(vararg names: String): Int {
        for (name in names) {
            val child = child(name)
            if (!child.exists()) continue
            val value = child.value
            if (value is Number) return value.toInt()
            value?.toString()?.toIntOrNull()?.let { return it }
        }
        return 0
    }
}
