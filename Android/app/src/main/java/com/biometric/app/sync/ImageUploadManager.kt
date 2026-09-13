package com.biometric.app.sync

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageUploadManager @Inject constructor() {

    private val storage = FirebaseStorage.getInstance().reference

    suspend fun uploadLogo(adminUid: String, uri: Uri): String? {
        val logoRef = storage.child("logos/$adminUid/app_logo.jpg")
        return try {
            logoRef.putFile(uri).await()
            logoRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            null
        }
    }
}
