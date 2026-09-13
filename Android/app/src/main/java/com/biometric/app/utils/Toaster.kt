package com.biometric.app.utils

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.biometric.app.R
import com.biometric.app.databinding.LayoutPremiumToastBinding

object Toaster {

    @Suppress("DEPRECATION")
    fun show(
        context: Context,
        message: String,
        isError: Boolean = false
    ) {
        val inflater = LayoutInflater.from(context)
        val binding = LayoutPremiumToastBinding.inflate(inflater)

        binding.tvMessage.text = message

        if (isError) {

            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    R.color.red_light
                )
            )

            /*
             * red_900 does not exist in this project.
             * Use Android's built-in dark red instead.
             */
            val errorColor = ContextCompat.getColor(
                context,
                android.R.color.holo_red_dark
            )

            binding.tvMessage.setTextColor(errorColor)

            binding.ivIcon.setImageResource(
                android.R.drawable.ic_dialog_alert
            )

            binding.ivIcon.imageTintList =
                ColorStateList.valueOf(errorColor)

        } else {

            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    R.color.colorSurfaceVariant
                )
            )

            binding.tvMessage.setTextColor(
                ContextCompat.getColor(
                    context,
                    R.color.text_primary
                )
            )

            binding.ivIcon.setImageResource(
                R.mipmap.ic_launcher_round
            )

            binding.ivIcon.imageTintList = null
        }

        val toast = Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            view = binding.root
            setGravity(
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                0,
                200
            )
        }

        toast.show()
    }
}