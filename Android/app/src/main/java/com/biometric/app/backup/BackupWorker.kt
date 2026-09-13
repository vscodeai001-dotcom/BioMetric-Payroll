package com.biometric.app.backup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.biometric.app.data.entity.RecycleBinItem
import com.biometric.app.domain.GoogleDriveBackupUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupUseCase: GoogleDriveBackupUseCase,
    private val firebaseSync: com.biometric.app.sync.FirebaseSyncManager,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "automated_business_backup"

        fun trigger(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME + "_manual",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(2, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME + "_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d("BackupWorker", "Starting automated business backup...")
        try {
            val backupJson = backupUseCase.generateBackupJson()
            
            // 1. Save to Internal Private Storage (Always reliable for app sessions)
            val internalFile = File(applicationContext.filesDir, "Biometric_Latest_AutoBackup.json")
            FileOutputStream(internalFile).use { it.write(backupJson.toByteArray()) }

            // 2. Save to Public Local Storage (survives app uninstalls)
            saveToPublicStorage(backupJson)

            // 3. Upload to Google Drive (Cloud persist & auto-restore)
            val driveManager = GoogleDriveManager(applicationContext)
            if (driveManager.isUserSignedIn()) {
                driveManager.uploadFile(internalFile)
                Log.d("BackupWorker", "Google Drive, Public & Private backup completed.")
            } else {
                Log.w("BackupWorker", "Drive not signed in. Local public/private backup updated.")
            }
            
            // 4. Auto-clean Recycle Bin (Older than 30 days)
            try {
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val binItems = firebaseSync.getDataFlow<RecycleBinItem>("recycle_bin").first()
                binItems.forEach { item ->
                    if (item.timestamp < thirtyDaysAgo) {
                        firebaseSync.deleteRecycleBinItem(item.id)
                    }
                }
            } catch (e: Exception) {
                Log.e("BackupWorker", "Recycle bin cleanup failed", e)
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e("BackupWorker", "Auto-backup failed", e)
            Result.retry()
        }
    }

    private fun saveToPublicStorage(json: String) {
        try {
            // Use a timestamped filename to ensure uniqueness and avoid "Failed to build unique file" errors
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
            val timestamp = sdf.format(java.util.Date())
            val fileName = "Biometric_Backup_${timestamp}.json"
            val relativePath = Environment.DIRECTORY_DOWNLOADS + File.separator + "BiometricApp" + File.separator
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val resolver = applicationContext.contentResolver
                // MediaStore.Downloads.EXTERNAL_CONTENT_URI requires API 29
                val downloadUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = resolver.insert(downloadUri, contentValues)

                uri?.let {
                    resolver.openOutputStream(it, "wt")?.use { os ->
                        os.write(json.toByteArray())
                    }
                    Log.d("BackupWorker", "Public storage backup successful: $relativePath$fileName")
                    
                    // Cleanup: Try to delete older backups in this folder to save space
                    cleanupOldBackups(resolver, relativePath)
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BiometricPayroll_Exports")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(json.toByteArray()) }
                Log.d("BackupWorker", "Public storage backup successful: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e("BackupWorker", "Public storage backup failed: ${e.message}")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupOldBackups(resolver: android.content.ContentResolver, relativePath: String) {
        try {
            val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(relativePath, "Biometric_Backup_%.json")
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            
            val cursor = resolver.query(queryUri, arrayOf(MediaStore.MediaColumns._ID), selection, selectionArgs, sortOrder)
            cursor?.use {
                // Keep only the last 3 backups
                if (it.count > 3) {
                    it.moveToPosition(2) // Skip first 3 (0, 1, 2)
                    while (it.moveToNext()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = android.content.ContentUris.withAppendedId(queryUri, id)
                        resolver.delete(uri, null, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BackupWorker", "Cleanup failed: ${e.message}")
        }
    }
}
