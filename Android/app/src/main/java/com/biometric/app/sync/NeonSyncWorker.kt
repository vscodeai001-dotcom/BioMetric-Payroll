package com.biometric.app.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class NeonSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val neonSync: NeonSyncManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        neonSync.syncAll()
        return Result.success()
    }
}
