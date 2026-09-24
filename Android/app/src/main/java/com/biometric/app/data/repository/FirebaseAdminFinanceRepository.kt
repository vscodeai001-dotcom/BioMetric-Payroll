package com.biometric.app.data.repository

import com.biometric.app.api.MoneyEntryDto
import com.biometric.app.api.TaxDeclarationDto
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.FirebaseSyncManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase read/write boundary for Admin finance data.
 *
 * This mirrors the Web finance data contract without reimplementing payroll
 * calculations. Payroll calculation/finalisation remains on the Web/SQL
 * business-rule boundary until it is independently verified for Android.
 */
@Singleton
class FirebaseAdminFinanceRepository @Inject constructor(
    private val firebaseSync: FirebaseSyncManager,
    private val sessionStore: MobileSessionStore
) {
    private fun ownerRef(): DatabaseReference =
        firebaseSync.getOwnerRef() ?: throw IllegalStateException("Firebase admin session is not initialized")

    private fun DataSnapshot.raw(name: String): Any? {
        val candidates = listOf(name, name.replaceFirstChar { it.lowercase() }, name.replaceFirstChar { it.uppercase() })
        return candidates.asSequence().map { child(it) }.firstOrNull { it.exists() }?.value
    }

    private fun DataSnapshot.int(name: String): Int = when (val value = raw(name)) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull() ?: 0
    }

    private fun DataSnapshot.double(name: String): Double = when (val value = raw(name)) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull() ?: 0.0
    }

    private fun DataSnapshot.long(name: String): Long = when (val value = raw(name)) {
        is Number -> value.toLong()
        else -> value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun DataSnapshot.string(name: String): String? = raw(name)?.toString()?.takeIf { it.isNotBlank() }

    private fun formatDate(value: Long): String {
        if (value <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(value))
    }

    suspend fun advances(unpaidOnly: Boolean = false): List<MoneyEntryDto> =
        ownerRef().child("advance_payments").get().await().children.mapNotNull { s ->
            val recovered = s.booleanAny("isRecovered", "IsRecovered")
            if (unpaidOnly && recovered) return@mapNotNull null
            MoneyEntryDto(
                id = stableId(s.string("advanceId") ?: s.key.orEmpty()),
                date = formatDate(s.long("date")),
                amount = s.double("amount"),
                type = s.string("advanceType") ?: s.string("type") ?: "Salary Advance",
                description = s.string("reason") ?: s.string("description"),
                paid = recovered,
                employeeId = s.int("employeeId")
            )
        }.sortedByDescending { it.date }

    suspend fun bonuses(): List<MoneyEntryDto> =
        ownerRef().child("bonus_records").get().await().children.map { s ->
            MoneyEntryDto(
                id = s.int("bonusId"),
                date = formatDate(s.long("bonusDate")),
                amount = s.double("amount"),
                type = s.string("type") ?: "Performance Bonus",
                description = s.string("description"),
                paid = s.int("payrollIdPaid") > 0,
                employeeId = s.int("employeeId")
            )
        }.sortedByDescending { it.date }

    suspend fun taxDeclarations(financialYear: Int): List<TaxDeclarationDto> =
        ownerRef().child("tax_declarations").get().await().children.mapNotNull { s ->
            if (s.int("financialYear") != financialYear) return@mapNotNull null
            TaxDeclarationDto(
                declarationId = s.int("declarationId"),
                financialYear = financialYear,
                regime = s.string("regime") ?: "New",
                section80C = s.double("section80C"),
                section80D = s.double("section80D"),
                hraRentPaid = s.double("hraRentPaid"),
                otherExemptions = s.double("otherExemptions"),
                status = s.string("status") ?: "Pending",
                adminRemarks = s.string("adminRemarks"),
                employeeId = s.int("employeeId")
            )
        }.sortedByDescending { it.declarationId }

    suspend fun approveTaxDeclaration(declarationId: Int, remarks: String = "Approved by Admin") {
        require(declarationId > 0) { "Valid declarationId is required." }
        ownerRef().child("tax_declarations").child(declarationId.toString()).updateChildren(
            mapOf(
                "status" to "Approved",
                "adminRemarks" to remarks,
                "approvalDate" to System.currentTimeMillis()
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("TaxDeclaration", "UPDATED", declarationId.toString())
    }

    suspend fun rejectTaxDeclaration(declarationId: Int, remarks: String) {
        require(declarationId > 0) { "Valid declarationId is required." }
        ownerRef().child("tax_declarations").child(declarationId.toString()).updateChildren(
            mapOf(
                "status" to "Rejected",
                "adminRemarks" to remarks,
                "approvalDate" to null
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("TaxDeclaration", "UPDATED", declarationId.toString())
    }

    suspend fun unlockTaxDeclaration(declarationId: Int) {
        require(declarationId > 0) { "Valid declarationId is required." }
        ownerRef().child("tax_declarations").child(declarationId.toString()).updateChildren(
            mapOf(
                "status" to "Draft",
                "approvalDate" to null
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("TaxDeclaration", "UPDATED", declarationId.toString())
    }

    suspend fun createAdvance(employeeId: Int, amount: Double, type: String?, date: String?) {
        require(employeeId > 0) { "Valid employee is required." }
        require(amount > 0.0) { "Positive amount is required." }
        val employee = ownerRef().child("employees").child(employeeId.toString()).get().await()
        val shopId = employee.string("shopId") ?: ""
        val id = UUID.randomUUID().toString()
        ownerRef().child("advance_payments").child(id).setValue(
            mapOf(
                "advanceId" to id,
                "employeeId" to employeeId,
                "shopId" to shopId,
                "amount" to amount,
                "date" to parseDate(date),
                "isRecovered" to false,
                "recoveryPaymentId" to null,
                "advanceType" to type
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("AdvancePayment", "ADDED", id)
    }

    suspend fun deleteAdvance(advanceId: String) {
        require(advanceId.isNotBlank()) { "Valid advanceId is required." }
        ownerRef().child("advance_payments").child(advanceId).removeValue().await()
        firebaseSync.notifyRealtimeChanged("AdvancePayment", "DELETED", advanceId)
    }

    suspend fun createBonus(employeeId: Int, amount: Double, description: String?, date: String?) {
        require(employeeId > 0) { "Valid employee is required." }
        require(amount > 0.0) { "Positive amount is required." }
        val id = nextBonusId()
        ownerRef().child("bonus_records").child(id.toString()).setValue(
            mapOf(
                "bonusId" to id,
                "employeeId" to employeeId,
                "amount" to amount,
                "description" to description,
                "bonusDate" to parseDate(date),
                "payrollIdPaid" to null
            )
        ).await()
        firebaseSync.notifyRealtimeChanged("BonusRecord", "ADDED", id.toString())
    }

    suspend fun deleteBonus(bonusId: Int) {
        require(bonusId > 0) { "Valid bonusId is required." }
        ownerRef().child("bonus_records").child(bonusId.toString()).removeValue().await()
        firebaseSync.notifyRealtimeChanged("BonusRecord", "DELETED", bonusId.toString())
    }

    private suspend fun nextBonusId(): Int {
        val max = ownerRef().child("bonus_records").get().await().children.maxOfOrNull { it.int("bonusId") } ?: 0
        return max + 1
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)?.time ?: System.currentTimeMillis() }
            .getOrDefault(System.currentTimeMillis())
    }

    private fun DataSnapshot.booleanAny(vararg names: String): Boolean {
        names.forEach { name ->
            val value = raw(name)
            if (value is Boolean) return value
            value?.toString()?.toBooleanStrictOrNull()?.let { return it }
        }
        return false
    }

    private fun stableId(value: String): Int =
        value.hashCode() and Int.MAX_VALUE
}
