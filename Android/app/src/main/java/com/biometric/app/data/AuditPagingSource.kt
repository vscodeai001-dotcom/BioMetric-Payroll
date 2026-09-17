package com.biometric.app.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.sync.FirebaseSyncManager
import kotlinx.coroutines.tasks.await

class AuditPagingSource(
    private val firebaseSync: FirebaseSyncManager,
    private val shopId: String?,
    private val start: Long,
    private val end: Long,
    private val search: String = ""
) : PagingSource<Long, AuditLog>() {

    override fun getRefreshKey(state: PagingState<Long, AuditLog>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestItemToPosition(anchorPosition)?.timestamp
        }
    }

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, AuditLog> {
        return try {
            val startKey = params.key ?: end
            var currentEndAt = startKey
            val allFetchedLogs = mutableListOf<AuditLog>()
            
            var attempts = 0
            val scanLimit = 10 // Fewer attempts but larger batches for faster results
            
            // OPTIMIZATION: Small batch for single days, large for broad scans
            val isSingleDay = (startKey - start) <= 86400000L
            val batchSize = if (isSingleDay) 100 else if (shopId.isNullOrEmpty()) params.loadSize else 400
            
            while (currentEndAt >= start && attempts < scanLimit) {
                attempts++
            val query = firebaseSync.getOwnerRef()?.child("audit_logs")
                ?.orderByChild("timestamp")
                ?.endAt(currentEndAt.toDouble())
                ?.limitToLast(batchSize)

            if (query == null) break

            val snapshot = try {
                query.get().await()
            } catch (e: Exception) {
                if (e.message?.contains("Index not defined") == true) {
                    // FALLBACK: Assign the result of the manual fetch to snapshot
                    firebaseSync.getOwnerRef()?.child("audit_logs")?.get()?.await()
                } else {
                    throw e
                }
            }

            if (snapshot == null) break
            
            val rawLogs = snapshot.children.mapNotNull { it.getValue(AuditLog::class.java) }
                .sortedByDescending { it.timestamp }
                
                if (rawLogs.isEmpty()) break

                // Filter for results matching this shop and within time range
                val filtered = rawLogs.filter { log ->
                    (shopId.isNullOrEmpty() || log.shopId == shopId) &&
                    matchesSearch(log) &&
                    log.timestamp >= start && 
                    log.timestamp <= startKey 
                }
                
                // Add to results, ensuring no duplicates
                filtered.forEach { log ->
                    if (allFetchedLogs.none { it.logId == log.logId }) {
                        allFetchedLogs.add(log)
                    }
                }

                val oldestRawTimestamp = rawLogs.last().timestamp
                currentEndAt = oldestRawTimestamp - 1
                
                // PERFORMANCE: If we found matches or reached the end of the range, break.
                // Otherwise, jump currentEndAt further to skip empty time gaps.
                if (allFetchedLogs.size >= params.loadSize || oldestRawTimestamp < start) {
                    break
                }
                
                if (filtered.isEmpty()) {
                    // JUMP: If this batch had no matches for this shop, skip ahead by 1 day equivalent
                    // but stay within overall range boundaries.
                    currentEndAt -= 86400000L 
                }
            }

            LoadResult.Page(
                data = allFetchedLogs,
                prevKey = null,
                nextKey = if (currentEndAt >= start) currentEndAt else null
            )
        } catch (e: Exception) {
            android.util.Log.e("AuditPaging", "Error loading audit logs: ${e.message}", e)
            LoadResult.Error(e)
        }
    }

    private fun matchesSearch(log: AuditLog): Boolean {
        val q = search.trim()
        if (q.isBlank()) return true
        val haystack = listOf(
            log.userDisplayName, log.userId, log.actorRole, log.module,
            log.action, log.targetId.orEmpty(), log.oldValue.orEmpty(), log.newValue.orEmpty()
        ).joinToString(" ")
        return haystack.contains(q, ignoreCase = true)
    }
}
