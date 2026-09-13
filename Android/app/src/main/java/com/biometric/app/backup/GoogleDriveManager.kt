@file:Suppress("DEPRECATION")
package com.biometric.app.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GoogleDriveManager(private val context: Context) {

    private val driveScope = Scope(DriveScopes.DRIVE_APPDATA)

    fun getSignInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    fun isUserSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(account, driveScope)
    }

    suspend fun uploadFile(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
            credential.selectedAccount = account.account

            val driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("Biometric Payroll").build()

            // Upload to appDataFolder space
            val googleFile = com.google.api.services.drive.model.File()
            googleFile.name = file.name
            googleFile.parents = listOf("appDataFolder")

            val mediaContent = FileContent("application/json", file)
            
            val uploadedFile = driveService.files().create(googleFile, mediaContent)
                .setFields("id")
                .execute()
                
            uploadedFile.id
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getLatestBackupFile(): com.google.api.services.drive.model.File? = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
            credential.selectedAccount = account.account

            val driveService = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName("Biometric Payroll").build()

            val result = driveService.files().list()
                .setSpaces("appDataFolder")
                .setOrderBy("createdTime desc")
                .setPageSize(1)
                .setFields("files(id, name, createdTime)")
                .execute()

            result.files.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun downloadFile(fileId: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext false
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
            credential.selectedAccount = account.account

            val driveService = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName("Biometric Payroll").build()

            outputFile.outputStream().use { 
                driveService.files().get(fileId).executeMediaAndDownloadTo(it)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
