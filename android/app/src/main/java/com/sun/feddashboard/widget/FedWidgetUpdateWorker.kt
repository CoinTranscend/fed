package com.sun.feddashboard.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sun.feddashboard.domain.FedEngine
import com.sun.feddashboard.domain.InflationLeadingEngine
import com.sun.feddashboard.domain.LaborLeadingEngine
import com.sun.feddashboard.domain.RecessionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that fetches all 5 indices from FRED and updates the widget.
 * Enqueued by FedWidgetProvider on onUpdate and on manual refresh.
 */
class FedWidgetUpdateWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Respect the Settings toggle — if disabled, just redraw from cache
        val widgetPrefs = ctx.getSharedPreferences("fed_widget_prefs", Context.MODE_PRIVATE)
        val enabled = widgetPrefs.getBoolean(FedWidgetDataStore.PREF_WIDGET_ENABLED, true)
        if (!enabled) {
            refreshWidgetUi()
            return@withContext Result.success()
        }

        val fredKey = FedWidgetDataStore.getFredKey(ctx)
        if (fredKey.isBlank()) {
            // No key configured — push current (possibly cached) data to widget
            refreshWidgetUi()
            return@withContext Result.success()
        }

        runCatching {
            val fed    = FedEngine.compute(fredKey)
            val liimsi = InflationLeadingEngine.compute(fredKey)
            val llmsi  = LaborLeadingEngine.compute(fredKey)
            val rri    = RecessionEngine.compute(fredKey)

            FedWidgetDataStore.save(ctx, FedWidgetDataStore.WidgetData(
                liimsi    = liimsi,
                llmsi     = llmsi,
                isi       = fed.isi,
                lsi       = fed.lsi,
                rri       = rri,
                updatedMs = System.currentTimeMillis(),
            ))
        }

        refreshWidgetUi()
        Result.success()
    }

    private fun refreshWidgetUi() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FedWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            FedWidgetProvider.updateAllWidgets(ctx, mgr, ids)
        }
    }
}
