package com.biometric.app.util

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.charts.PieChart
import android.widget.TextView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import java.text.NumberFormat
import java.util.Locale
import com.biometric.app.R

object ChartStyleManager {

    fun applyChartStyle(chart: Chart<*>, context: Context) {
        val textColorPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textColorSecondary = ContextCompat.getColor(context, R.color.text_secondary)
        val gridColor = ContextCompat.getColor(context, R.color.divider)

        chart.apply {
            description.isEnabled = false
            legend.textColor = textColorSecondary
            setNoDataTextColor(textColorPrimary)
            
            if (this is BarLineChartBase<*>) {
                marker = CustomMarkerView(context, R.layout.view_chart_marker)
                setDrawMarkers(true)
                
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(false)
                    textColor = textColorSecondary
                    axisLineColor = textColorSecondary
                }
                
                axisLeft.apply {
                    setDrawGridLines(true)
                    this.gridColor = gridColor
                    textColor = textColorSecondary
                    axisLineColor = textColorSecondary
                }
                
                axisRight.isEnabled = false
                setDrawGridBackground(false)
                setDragEnabled(true)
                setScaleEnabled(false)
                setPinchZoom(false)
            } else if (this is PieChart) {
                setHoleColor(Color.TRANSPARENT)
                setTransparentCircleColor(Color.WHITE)
                setTransparentCircleAlpha(50)
                setCenterTextColor(textColorPrimary)
                setEntryLabelColor(Color.WHITE)
            }
        }
    }

    class CustomMarkerView(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {
        private val tvContent: TextView = findViewById(R.id.tvContent)
        private val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

        override fun refreshContent(e: Entry, highlight: Highlight) {
            tvContent.text = if (e.y >= 100) currencyFormat.format(e.y) else String.format(Locale.getDefault(), "%.1f", e.y)
            super.refreshContent(e, highlight)
        }

        override fun getOffset(): MPPointF {
            return MPPointF((-(width / 2)).toFloat(), (-height).toFloat())
        }
    }
}

