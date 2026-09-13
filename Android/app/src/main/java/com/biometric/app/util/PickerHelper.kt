package com.biometric.app.util

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import java.text.SimpleDateFormat
import java.util.*

object PickerHelper {

    fun showSmartPicker(
        context: Context,
        period: String,
        currentDate: Calendar,
        onDateSelected: (Calendar) -> Unit
    ) {
        showGridDatePicker(context, currentDate, onDateSelected)
    }

    private fun showGridDatePicker(context: Context, currentDate: Calendar, onDateSelected: (Calendar) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_grid_date_picker, null)
        val tvHeader = dialogView.findViewById<TextView>(R.id.tvPickerHeader)
        val rvGrid = dialogView.findViewById<RecyclerView>(R.id.rvPickerGrid)
        val btnPrev = dialogView.findViewById<View>(R.id.btnPrevMonth)
        val btnNext = dialogView.findViewById<View>(R.id.btnNextMonth)
        
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()

        var viewMode = "DAYS" 
        val workingCal = (currentDate.clone() as Calendar)

        fun updateUI() {
            if (viewMode == "DAYS") {
                btnPrev.visibility = View.VISIBLE
                btnNext.visibility = View.VISIBLE

                val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                tvHeader.text = sdf.format(workingCal.time)
                
                rvGrid.layoutManager = GridLayoutManager(context, 7)
                val items = mutableListOf<String>()
                
                items.addAll(listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"))
                
                val tempCal = (workingCal.clone() as Calendar)
                tempCal.set(Calendar.DAY_OF_MONTH, 1)
                val firstDayOfWeek = tempCal.get(Calendar.DAY_OF_WEEK) - 1
                val daysInMonth = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
                
                for (i in 0 until firstDayOfWeek) items.add("")
                for (i in 1..daysInMonth) items.add(i.toString())
                
                rvGrid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val tv = TextView(context).apply {
                            layoutParams = RecyclerView.LayoutParams(-1, (48 * context.resources.displayMetrics.density).toInt())
                            gravity = Gravity.CENTER
                            textSize = 14f
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        return object : RecyclerView.ViewHolder(tv) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val text = items[position]
                        val tv = holder.itemView as TextView
                        tv.text = text
                        
                        if (text.isNotEmpty() && position >= 7) {
                            tv.setBackgroundResource(R.drawable.item_selectable_background_rounded)
                            tv.setOnClickListener {
                                workingCal.set(Calendar.DAY_OF_MONTH, text.toInt())
                                onDateSelected(workingCal)
                                dialog.dismiss()
                            }
                            
                            if (workingCal.get(Calendar.MONTH) == currentDate.get(Calendar.MONTH) && 
                                text.toInt() == currentDate.get(Calendar.DAY_OF_MONTH) && 
                                workingCal.get(Calendar.YEAR) == currentDate.get(Calendar.YEAR)) {
                                tv.setTextColor(ContextCompat.getColor(context, android.R.color.white))
                                tv.setBackgroundResource(R.drawable.bg_pill_clickable) 
                            } else {
                                tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                            }
                        } else if (position < 7) {
                            tv.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                            tv.setTypeface(null, Typeface.BOLD)
                            tv.setOnClickListener(null)
                            tv.setBackgroundResource(0)
                        } else {
                            tv.setOnClickListener(null)
                            tv.setBackgroundResource(0)
                        }
                    }
                    override fun getItemCount() = items.size
                }
            } else {
                btnPrev.visibility = View.GONE
                btnNext.visibility = View.GONE

                tvHeader.text = "Select Month"
                rvGrid.layoutManager = GridLayoutManager(context, 3)
                val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                
                rvGrid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val tv = TextView(context).apply {
                            layoutParams = RecyclerView.LayoutParams(-1, (60 * context.resources.displayMetrics.density).toInt())
                            gravity = Gravity.CENTER
                            textSize = 16f
                            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        }
                        return object : RecyclerView.ViewHolder(tv) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val tv = holder.itemView as TextView
                        tv.text = months[position]
                        tv.setBackgroundResource(R.drawable.item_selectable_background_rounded)
                        tv.setOnClickListener {
                            workingCal.set(Calendar.MONTH, position)
                            viewMode = "DAYS"
                            updateUI()
                        }
                        if (position == workingCal.get(Calendar.MONTH)) {
                             tv.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                             tv.setTypeface(null, Typeface.BOLD)
                        } else {
                             tv.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                             tv.setTypeface(null, Typeface.NORMAL)
                        }
                    }
                    override fun getItemCount() = 12
                }
            }
        }

        btnPrev.setOnClickListener {
            workingCal.add(Calendar.MONTH, -1)
            updateUI()
        }

        btnNext.setOnClickListener {
            workingCal.add(Calendar.MONTH, 1)
            updateUI()
        }

        tvHeader.setOnClickListener {
            viewMode = if (viewMode == "DAYS") "MONTHS" else "DAYS"
            updateUI()
        }

        updateUI()
        dialog.show()
    }
}
