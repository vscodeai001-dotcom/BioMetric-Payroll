package com.biometric.app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.biometric.app.data.entity.*
import com.biometric.app.sync.FirebaseSyncManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataSafetyManager @Inject constructor(
    private val firebaseSync: FirebaseSyncManager
) {
    private val gson = Gson()
    private val auth = FirebaseAuth.getInstance()

    suspend fun recordDeletion(module: String, recordId: String, item: Any, itemName: String) {
        val snapshotJson = gson.toJson(item)
        val user = auth.currentUser
        
        val shopId = when (item) {
            is Employee -> item.shopId
            is Attendance -> item.shopId
            is Shop -> item.shopId
            is ShopClosedDay -> item.shopId
            is AdvancePayment -> item.shopId
            else -> null
        }

        val binItem = RecycleBinItem(
            recordId = recordId,
            shopId = shopId,
            module = module,
            actionType = "DELETED",
            snapshotJson = snapshotJson,
            userId = user?.uid ?: "unknown",
            userName = user?.displayName ?: "Unknown User",
            itemName = itemName,
            timestamp = System.currentTimeMillis()
        )
        
        firebaseSync.pushRecycleBin(binItem)
        recordAudit(module, "DELETE", itemName, null, snapshotJson, shopId)
    }

    suspend fun recordUpdate(module: String, recordId: String, oldItem: Any, newItem: Any, itemName: String) {
        val oldJson = gson.toJson(oldItem)
        val newJson = gson.toJson(newItem)
        val user = auth.currentUser

        val shopId = when (newItem) {
            is Employee -> newItem.shopId
            is Attendance -> newItem.shopId
            is Shop -> newItem.shopId
            is ShopClosedDay -> newItem.shopId
            is AdvancePayment -> newItem.shopId
            else -> null
        }
        
        val binItem = RecycleBinItem(
            recordId = recordId,
            shopId = shopId,
            module = module,
            actionType = "UPDATED",
            snapshotJson = newJson,
            previousValueJson = oldJson,
            userId = user?.uid ?: "unknown",
            userName = user?.displayName ?: "Unknown User",
            itemName = itemName,
            timestamp = System.currentTimeMillis()
        )
        
        firebaseSync.pushRecycleBin(binItem)
        recordAudit(module, "UPDATE", itemName, oldJson, newJson, shopId)
    }

    private suspend fun recordAudit(module: String, action: String, itemName: String, oldVal: String?, newVal: String?, shopId: String? = null) {
        val user = auth.currentUser
        val log = AuditLog(
            shopId = shopId ?: "Global",
            action = action,
            module = "$module: $itemName",
            oldValue = oldVal?.take(500),
            newValue = newVal?.take(500),
            userDisplayName = user?.displayName ?: "Unknown",
            userId = user?.uid ?: "unknown",
            timestamp = System.currentTimeMillis()
        )
        firebaseSync.pushAuditLog(log)
    }

    suspend fun restoreItem(binItem: RecycleBinItem): Boolean {
        return try {
            if (binItem.snapshotJson.isBlank() || binItem.recordId.isBlank()) return false
            val json = binItem.snapshotJson
            when (binItem.module) {
                "STAFF" -> {
                    val employee = gson.fromJson(json, Employee::class.java)
                    employee.isActive = true
                    employee.terminateDate = null
                    firebaseSync.pushEmployee(employee)
                }
                "ATTENDANCE" -> firebaseSync.pushAttendance(gson.fromJson(json, Attendance::class.java))
                "SHOP" -> {
                    val shop = gson.fromJson(json, Shop::class.java)
                    shop.isActive = true
                    firebaseSync.pushShop(shop)
                }
                "REMINDER" -> firebaseSync.pushReminder(gson.fromJson(json, Reminder::class.java))
                "CLOSED_DAY" -> firebaseSync.pushClosedDay(gson.fromJson(json, ShopClosedDay::class.java))
                "ADVANCE" -> firebaseSync.pushAdvance(gson.fromJson(json, AdvancePayment::class.java))
                "STAFF_HISTORY" -> firebaseSync.pushHistory(gson.fromJson(json, EmployeeHistory::class.java))
                else -> return false
            }
            
            recordAudit(binItem.module, "RESTORE", binItem.itemName, null, binItem.snapshotJson, binItem.shopId)
            firebaseSync.deleteRecycleBinItem(binItem.id)
            true
        } catch (e: Exception) {
            Log.e("DataSafetyManager", "Restore failed", e)
            false
        }
    }

    /**
     * Permanently removes only the recycle-bin snapshot. It never recreates,
     * deletes, or modifies the underlying business record and never touches
     * Firebase Authentication.
     */
    suspend fun permanentlyDeleteRecycleBinItem(binItem: RecycleBinItem): Boolean {
        return try {
            val user = auth.currentUser ?: return false
            if (binItem.id.isBlank()) return false
            firebaseSync.deleteRecycleBinItem(binItem.id)
            val log = AuditLog(
                shopId = binItem.shopId ?: "Global",
                action = "RECYCLE_BIN_PURGE",
                module = "${binItem.module}: ${binItem.itemName}",
                oldValue = binItem.snapshotJson.take(500),
                newValue = null,
                userDisplayName = user.displayName ?: user.email?.substringBefore("@") ?: "Unknown",
                userId = user.uid,
                timestamp = System.currentTimeMillis()
            )
            firebaseSync.pushAuditLog(log)
            true
        } catch (e: Exception) {
            Log.e("DataSafetyManager", "Recycle-bin purge failed", e)
            false
        }
    }
}
