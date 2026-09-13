package com.biometric.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import java.io.File
import java.io.FileOutputStream

import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun compressBitmap(bitmap: Bitmap, quality: Int = 70): Bitmap {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val byteArray = stream.toByteArray()
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    fun base64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    fun shareViewAsImage(view: View, fileName: String, viewsToHide: List<View> = emptyList(), watermarkText: String? = null) {
        // Temporarily hide specified views
        viewsToHide.forEach { it.visibility = View.INVISIBLE }
        
        val bitmap = captureView(view, watermarkText)
        
        // Restore visibility
        viewsToHide.forEach { it.visibility = View.VISIBLE }

        saveBitmapToCache(view.context, bitmap, fileName)?.let { uri ->
            shareImageUri(view.context, uri)
        }
    }

    private fun captureView(view: View, watermarkText: String? = null): Bitmap {
        val isDark = (view.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                     android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        val bitmap = createBitmap(view.width, view.height)
        bitmap.applyCanvas {
            // Dynamic background based on current theme to ensure visibility
            drawColor(if (isDark) "#020617".toColorInt() else Color.WHITE)
            
            view.draw(this)

            // Adaptive watermark
            watermarkText?.let { text ->
                val paint = Paint().apply {
                    color = if (isDark) Color.LTGRAY else Color.GRAY
                    alpha = 80
                    textSize = 32f
                    isAntiAlias = true
                }
                val x = view.width - paint.measureText(text) - 40f
                val y = view.height - 20f
                drawText(text, x, y, paint)
            }
        }

        return bitmap
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            val cachePath = File(context.cacheDir, "shared_images")
            cachePath.mkdirs()
            val file = File(cachePath, "$fileName.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun shareImageUri(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Staff Details"))
    }
}
