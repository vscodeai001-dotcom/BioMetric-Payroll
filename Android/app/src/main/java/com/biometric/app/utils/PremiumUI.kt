package com.biometric.app.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import com.biometric.app.R
import com.biometric.app.databinding.DialogSuccessPremiumBinding
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.MotionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object PremiumUI {

    fun showSuccess(context: Context, title: String, message: String) {
        try {
            val dialog = Dialog(context)
            val binding = DialogSuccessPremiumBinding.inflate(LayoutInflater.from(context))
            dialog.setContentView(binding.root)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            
            binding.tvSuccessTitle.text = title
            binding.tvSuccessMessage.text = message
            
            val drawable = ContextCompat.getDrawable(context, R.drawable.avd_success)
            if (drawable is AnimatedVectorDrawable) {
                binding.ivSuccessFallback.setImageDrawable(drawable)
                drawable.start()
            } else {
                binding.ivSuccessFallback.setImageResource(R.drawable.ic_success_checkmark)
            }

            // Premium Scale Animation
            val scaleAnim = AnimationUtils.loadAnimation(context, R.anim.scale_up_premium)
            binding.root.startAnimation(scaleAnim)
            HapticUtil.vibrateSuccess(binding.root)
            
            MotionManager.applyDialogAnimation(dialog)

            dialog.show()

            // Auto dismiss after 2 seconds
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                if (dialog.isShowing) {
                    try {
                        dialog.dismiss()
                    } catch (e: Exception) {
                        Log.e("PremiumUI", "Error dismissing dialog", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PremiumUI", "Error showing success dialog", e)
        }
    }
}