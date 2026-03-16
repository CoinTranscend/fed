package com.sun.feddashboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

import com.sun.feddashboard.MainActivity
import com.sun.feddashboard.R
import com.sun.feddashboard.domain.ReleaseSchedule
import com.sun.feddashboard.model.ChartPoint
import java.time.LocalDate
import java.util.Calendar

/**
 * Fed Dashboard home-screen widget.
 *
 * Displays 5 indices (LIIMSI, LLMSI, ISI, LSI, RRI) with:
 *   - Current score + month-over-month delta
 *   - Regime label (color-coded)
 *   - 6-month sparkline drawn as a Bitmap
 *   - Next scheduled economic release (nearest non-daily release)
 *   - Refresh button + full-widget tap launches the app
 *
 * Color theme: deep sapphire blue (distinct from app's teal/green palette).
 */
class FedWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Render cached data immediately. No background fetch is scheduled here —
        // the widget is manual-refresh-only (user taps ↻), matching the main app's behaviour.
        updateAllWidgets(ctx, mgr, ids)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FedWidgetProvider::class.java))
            if (ids.isEmpty()) return
            // Manual refresh — use REPLACE to guarantee a fresh fetch starts immediately.
            // No loading-state wipe: just update the timestamp text so the user gets tap feedback
            // without blanking out all the indicator rows.
            val refreshingViews = buildViews(ctx, FedWidgetDataStore.load(ctx))
            refreshingViews.setTextViewText(R.id.tv_widget_updated, "↻ …")
            ids.forEach { id -> mgr.updateAppWidget(id, refreshingViews) }
            enqueueRefresh(ctx)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    companion object {

        const val ACTION_REFRESH = "com.sun.feddashboard.WIDGET_REFRESH"

        // ── Light sky-blue palette (dark text on light background) ───────────
        private val CLR_SCORE_POS   = Color.parseColor("#0D6E2E")  // forest green — positive
        private val CLR_SCORE_NEG   = Color.parseColor("#B71C1C")  // dark red — negative
        private val CLR_SCORE_ZERO  = Color.parseColor("#1A4F78")  // dark navy — neutral
        private val CLR_DELTA_POS   = Color.parseColor("#1B7A3A")  // green up arrow
        private val CLR_DELTA_NEG   = Color.parseColor("#C62828")  // red down arrow
        private val CLR_DELTA_ZERO  = Color.parseColor("#4A7090")  // muted navy

        // Regime colors — readable on light blue bg
        private val CLR_REGIME_BEST    = Color.parseColor("#0D6E9A") // ELEVATED/STRONG — deep teal
        private val CLR_REGIME_GOOD    = Color.parseColor("#1976A8") // RISING/MODERATE
        private val CLR_REGIME_NEUTRAL = Color.parseColor("#4A7A9B") // ANCHORED/SOFTENING
        private val CLR_REGIME_WARN    = Color.parseColor("#B05E00") // COOLING/WEAK → dark amber
        private val CLR_REGIME_BAD     = Color.parseColor("#B71C1C") // DEFLATIONARY/CRITICAL

        // Sparkline colors — darker blue on light bg
        private val SPARK_LINE   = Color.parseColor("#1565C0")
        private val SPARK_FILL   = Color.argb(40, 21, 101, 192)
        private val SPARK_DOT    = Color.parseColor("#0D47A1")
        private val SPARK_ZLINE  = Color.argb(80, 30, 80, 140)     // subtle zero line

        /** Manual refresh from ↻ button — always starts a new worker. */
        fun enqueueRefresh(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FedWidgetUpdateWorker>().build(),
            )
        }

        private const val WORK_NAME = "fed_widget_refresh"

        fun updateAllWidgets(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val data  = FedWidgetDataStore.load(ctx)
            val views = buildViews(ctx, data)
            ids.forEach { id -> mgr.updateAppWidget(id, views) }
        }

        // ── RemoteViews builder ───────────────────────────────────────────────

        private fun buildViews(ctx: Context, data: FedWidgetDataStore.WidgetData): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_fed)

            // Full-widget tap → open app
            val launchPi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchPi)

            // Refresh button
            val refreshPi = PendingIntent.getBroadcast(
                ctx, 1,
                Intent(ctx, FedWidgetProvider::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.btn_widget_refresh, refreshPi)

            // ── Populate each index row ───────────────────────────────────────

            data.liimsi?.let { r ->
                val delta = deltaOf(r.points)
                views.setTextViewText(R.id.tv_liimsi_score,  fmtScore(r.current))
                views.setTextViewText(R.id.tv_liimsi_delta,  fmtDelta(delta))
                views.setTextViewText(R.id.tv_liimsi_regime, r.regime)
                views.setTextColor(R.id.tv_liimsi_score,  scoreColor(r.current))
                views.setTextColor(R.id.tv_liimsi_delta,  deltaColor(delta))
                views.setTextColor(R.id.tv_liimsi_regime, regimeColor(r.regime))
                views.setImageViewBitmap(R.id.iv_liimsi_chart, sparkline(r.points))
            }

            data.llmsi?.let { r ->
                val delta = deltaOf(r.points)
                views.setTextViewText(R.id.tv_llmsi_score,  fmtScore(r.current))
                views.setTextViewText(R.id.tv_llmsi_delta,  fmtDelta(delta))
                views.setTextViewText(R.id.tv_llmsi_regime, r.regime)
                views.setTextColor(R.id.tv_llmsi_score,  scoreColor(r.current))
                views.setTextColor(R.id.tv_llmsi_delta,  deltaColor(delta))
                views.setTextColor(R.id.tv_llmsi_regime, regimeColor(r.regime))
                views.setImageViewBitmap(R.id.iv_llmsi_chart, sparkline(r.points))
            }

            data.isi?.let { r ->
                val delta = deltaOf(r.points)
                views.setTextViewText(R.id.tv_isi_score,  fmtScore(r.current))
                views.setTextViewText(R.id.tv_isi_delta,  fmtDelta(delta))
                views.setTextViewText(R.id.tv_isi_regime, r.regime)
                views.setTextColor(R.id.tv_isi_score,  scoreColor(r.current))
                views.setTextColor(R.id.tv_isi_delta,  deltaColor(delta))
                views.setTextColor(R.id.tv_isi_regime, regimeColor(r.regime))
                views.setImageViewBitmap(R.id.iv_isi_chart, sparkline(r.points))
            }

            data.lsi?.let { r ->
                val delta = deltaOf(r.points)
                views.setTextViewText(R.id.tv_lsi_score,  fmtScore(r.current))
                views.setTextViewText(R.id.tv_lsi_delta,  fmtDelta(delta))
                views.setTextViewText(R.id.tv_lsi_regime, r.regime)
                views.setTextColor(R.id.tv_lsi_score,  scoreColor(r.current))
                views.setTextColor(R.id.tv_lsi_delta,  deltaColor(delta))
                views.setTextColor(R.id.tv_lsi_regime, regimeColor(r.regime))
                views.setImageViewBitmap(R.id.iv_lsi_chart, sparkline(r.points))
            }

            data.rri?.let { r ->
                val delta = deltaOf(r.points)
                views.setTextViewText(R.id.tv_rri_score,  fmtScore(r.current))
                views.setTextViewText(R.id.tv_rri_delta,  fmtDelta(delta))
                views.setTextViewText(R.id.tv_rri_regime, r.regime)
                views.setTextColor(R.id.tv_rri_score,  scoreColor(r.current))
                views.setTextColor(R.id.tv_rri_delta,  deltaColor(delta))
                views.setTextColor(R.id.tv_rri_regime, regimeColor(r.regime))
                views.setImageViewBitmap(R.id.iv_rri_chart, sparkline(r.points))
            }

            // ── Next release ──────────────────────────────────────────────────
            views.setTextViewText(R.id.tv_next_release, nextReleaseText())

            // ── Updated timestamp ─────────────────────────────────────────────
            val ts = if (data.updatedMs > 0L) {
                val c = Calendar.getInstance().apply { timeInMillis = data.updatedMs }
                val h = c.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
                "$h:$m"
            } else "tap ↻"
            views.setTextViewText(R.id.tv_widget_updated, ts)

            return views
        }

        // ── Sparkline bitmap ─────────────────────────────────────────────────
        //  Draws last 6 data points as a filled line chart on a transparent bitmap.
        //  The ImageView uses scaleType=fitXY so it stretches to the row height.

        private fun sparkline(points: List<ChartPoint>): Bitmap {
            val bmpW = 280; val bmpH = 56
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val pts = points.takeLast(6)
            if (pts.size < 2) return bmp

            val vals = pts.map { it.value }
            val minV = vals.min()
            val maxV = vals.max()
            val range = (maxV - minV).coerceAtLeast(0.02f)

            val padH = 6f; val padV = 8f
            val usW = bmpW - 2 * padH
            val usH = bmpH - 2 * padV

            fun xAt(i: Int) = padH + i * usW / (pts.size - 1)
            fun yAt(v: Float) = padV + usH - (v - minV) / range * usH

            // Subtle zero-line if range spans zero
            if (minV < 0f && maxV > 0f) {
                val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SPARK_ZLINE
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
                }
                val zy = yAt(0f)
                canvas.drawLine(padH, zy, bmpW - padH, zy, zPaint)
            }

            // Fill area under line
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SPARK_FILL
                style = Paint.Style.FILL
            }
            val fillPath = Path().apply {
                moveTo(xAt(0), bmpH.toFloat())
                lineTo(xAt(0), yAt(vals[0]))
                for (i in 1 until pts.size) lineTo(xAt(i), yAt(vals[i]))
                lineTo(xAt(pts.size - 1), bmpH.toFloat())
                close()
            }
            canvas.drawPath(fillPath, fillPaint)

            // Line
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SPARK_LINE
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                strokeCap  = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val linePath = Path().apply {
                moveTo(xAt(0), yAt(vals[0]))
                for (i in 1 until pts.size) lineTo(xAt(i), yAt(vals[i]))
            }
            canvas.drawPath(linePath, linePaint)

            // Terminal dot
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SPARK_DOT
                style = Paint.Style.FILL
            }
            canvas.drawCircle(xAt(pts.size - 1), yAt(vals.last()), 2.5f, dotPaint)

            return bmp
        }

        // ── Next release computation ──────────────────────────────────────────

        private fun nextReleaseText(): String {
            val result = ReleaseSchedule.nextSingleRelease(
                ReleaseSchedule.widgetSeriesIds()
            ) ?: return "Next: –"
            return "Next: $result"
        }

        // ── Formatting helpers ────────────────────────────────────────────────

        private fun fmtScore(v: Float): String =
            if (v >= 0f) "+%.2f".format(v) else "%.2f".format(v)

        private fun deltaOf(points: List<ChartPoint>): Float {
            if (points.size < 2) return 0f
            return points.last().value - points[points.size - 2].value
        }

        private fun fmtDelta(delta: Float): String = when {
            delta >  0.005f -> "▲ %.2f".format(delta)
            delta < -0.005f -> "▼ %.2f".format(-delta)
            else            -> "● 0.00"
        }

        private fun scoreColor(v: Float): Int = when {
            v >  0.3f -> CLR_SCORE_POS
            v < -0.3f -> CLR_SCORE_NEG
            else      -> CLR_SCORE_ZERO
        }

        private fun deltaColor(delta: Float): Int = when {
            delta >  0.005f -> CLR_DELTA_POS
            delta < -0.005f -> CLR_DELTA_NEG
            else            -> CLR_DELTA_ZERO
        }

        /**
         * Maps regime strings from all 5 engines to sapphire-palette colors.
         * Warm (amber/salmon) = stress signals; cool (blue) = healthy/low-risk.
         */
        private fun regimeColor(regime: String): Int = when (regime.uppercase()) {
            // LIIMSI: high inflation = warm, low = cool
            "ELEVATED"     -> CLR_REGIME_BEST    // hot inflation signal
            "RISING"       -> CLR_REGIME_GOOD
            "ANCHORED"     -> CLR_REGIME_NEUTRAL
            "COOLING"      -> CLR_REGIME_WARN
            "DEFLATIONARY" -> CLR_REGIME_BAD

            // LLMSI / LSI: strong labor = cool, weak = warm
            "STRONG"       -> CLR_REGIME_BEST
            "MODERATE"     -> CLR_REGIME_GOOD
            "SOFTENING"    -> CLR_REGIME_WARN
            "WEAK"         -> CLR_REGIME_BAD
            "CRITICAL"     -> CLR_REGIME_BAD

            // ISI (inflation coincident — same as LIIMSI mapping)
            "HOT"          -> CLR_REGIME_BEST
            "WARM"         -> CLR_REGIME_GOOD
            "NEUTRAL"      -> CLR_REGIME_NEUTRAL
            "COLD"         -> CLR_REGIME_WARN
            "FRIGID"       -> CLR_REGIME_BAD

            // RRI: low risk = cool, high risk = warm
            "STABLE"       -> CLR_REGIME_GOOD
            "CAUTION"      -> CLR_REGIME_WARN
            "WARNING"      -> Color.parseColor("#B05E00")  // dark amber
            "SEVERE"       -> CLR_REGIME_BAD

            else           -> CLR_REGIME_NEUTRAL
        }
    }
}
