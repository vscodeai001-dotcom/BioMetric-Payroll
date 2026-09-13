package com.biometric.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.biometric.app.R

class DashboardWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_dashboard)
        
        // Data would typically be fetched from a ContentProvider or shared database
        // For now, we use placeholders as a full background fetch is complex
        views.setTextViewText(R.id.tvWidgetSales, "₹ ---")
        views.setTextViewText(R.id.tvWidgetStaff, "0 Staff")

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
