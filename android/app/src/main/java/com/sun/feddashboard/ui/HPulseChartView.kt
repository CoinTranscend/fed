package com.sun.feddashboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sun.feddashboard.model.HPulsePoint

/**
 * Custom 0–100 stress chart for the HPulse Household Pulse Index.
 *
 * Draws three solid lines (Burn / Middle / Buffer tier scores) against
 * four color-coded background bands at 0/25/50/75/100, with a legend
 * in the top-left corner and latest-value callouts on the right margin.
 */
class HPulseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var points: List<HPulsePoint> = emptyList()
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    // ── Tier line colors ──────────────────────────────────────────────────────

    private val COLOR_BURN   = Color.parseColor("#EF5350")   // red
    private val COLOR_MIDDLE = Color.parseColor("#F9A825")   // amber
    private val COLOR_BUFFER = Color.parseColor("#66BB6A")   // green

    // ── Paints ────────────────────────────────────────────────────────────────

    private val burnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#EF5350")
        strokeWidth = 2.5f * dp
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val middlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#F9A825")
        strokeWidth = 2f * dp
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#66BB6A")
        strokeWidth = 1.5f * dp
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#D0D0D0")
        strokeWidth = 0.7f * dp
        style       = Paint.Style.STROKE
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#9E9E9E")
        textSize  = 7.5f * dp
        textAlign = Paint.Align.RIGHT
        typeface  = Typeface.MONOSPACE
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#9E9E9E")
        textSize  = 7.5f * dp
        textAlign = Paint.Align.CENTER
    }

    private val calloutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize       = 9f * dp
        isFakeBoldText = true
        textAlign      = Paint.Align.LEFT
    }

    private val legendLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        strokeCap   = Paint.Cap.ROUND
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.5f * dp
    }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color          = Color.WHITE
        textSize       = 7f * dp
        isFakeBoldText = true
        textAlign      = Paint.Align.CENTER
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#9E9E9E")
        textSize  = 13f * dp
        textAlign = Paint.Align.CENTER
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private val PAD_L get() = 30f * dp   // y-axis labels (0, 25, 50, 75, 100)
    private val PAD_R get() = 40f * dp   // right callouts
    private val PAD_T get() = 22f * dp   // legend row
    private val PAD_B get() = 17f * dp   // x-axis labels

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.isEmpty()) {
            canvas.drawText("No data — tap ↻ to fetch", width / 2f, height / 2f, noDataPaint)
            return
        }

        val padL = PAD_L; val padR = PAD_R; val padT = PAD_T; val padB = PAD_B
        val chartW = width  - padL - padR
        val chartH = height - padT - padB
        if (chartW <= 0f || chartH <= 0f) return

        val n = points.size
        fun xOf(i: Int) = padL + i * chartW / (n - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - v / 100f)  // fixed 0–100 scale

        // 1. Stress band backgrounds
        drawBands(canvas, padL, padT, chartW, chartH)

        // 2. Gridlines + y-axis labels at band boundaries
        for (level in listOf(0, 25, 50, 75, 100)) {
            val y = yOf(level.toFloat()).coerceIn(padT, padT + chartH)
            canvas.drawLine(padL, y, padL + chartW, y, gridPaint)
            val lbl = level.toString()
            canvas.drawText(lbl, padL - 3f * dp, y + axisLabelPaint.textSize * 0.38f, axisLabelPaint)
        }

        // 3. Buffer line (draw first so it's behind others)
        drawLine(canvas, points.map { it.bufferScore }, ::xOf, ::yOf, bufferPaint)

        // 4. Middle line
        drawLine(canvas, points.map { it.middleScore }, ::xOf, ::yOf, middlePaint)

        // 5. Burn line (on top — most stressed tier)
        drawLine(canvas, points.map { it.burnScore }, ::xOf, ::yOf, burnPaint)

        // 6. X-axis labels — year boundaries
        val labelY = padT + chartH + 13f * dp
        var lastYr = ""
        for (i in points.indices) {
            val lbl = points[i].monthLabel        // "Jan '24"
            val yr  = lbl.substringAfter("'", "")
            val mon = lbl.substringBefore(" ")
            if (yr.isNotEmpty() && (mon == "Jan" || i == 0) && yr != lastYr) {
                canvas.drawText("'$yr", xOf(i), labelY, xLabelPaint)
                lastYr = yr
            }
        }

        // 7. Right-side latest-value callouts
        val calloutX = padL + chartW + 4f * dp
        val lastPt   = points.last()

        calloutPaint.color = COLOR_BURN
        canvas.drawText("%.0f".format(lastPt.burnScore), calloutX,
            yOf(lastPt.burnScore).coerceIn(padT + 9f * dp, padT + chartH - 2f * dp), calloutPaint)

        calloutPaint.color = COLOR_MIDDLE
        canvas.drawText("%.0f".format(lastPt.middleScore), calloutX,
            yOf(lastPt.middleScore).coerceIn(padT + 9f * dp, padT + chartH - 2f * dp), calloutPaint)

        calloutPaint.color = COLOR_BUFFER
        canvas.drawText("%.0f".format(lastPt.bufferScore), calloutX,
            yOf(lastPt.bufferScore).coerceIn(padT + 9f * dp, padT + chartH - 2f * dp), calloutPaint)

        // 8. Band label badge top-right (derived from composite of last point)
        val bandLabel = bandLabel(lastPt.composite)
        val bandColor = bandColor(lastPt.composite)
        drawBadge(canvas, bandLabel, bandColor, right = width - 2f * dp, top = padT - 16f * dp)

        // 9. Legend — top-left
        drawLegend(canvas, padL, 9f * dp)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun drawLine(
        canvas: Canvas,
        values: List<Float>,
        xOf: (Int) -> Float,
        yOf: (Float) -> Float,
        paint: Paint,
    ) {
        if (values.size < 2) return
        val path = Path()
        path.moveTo(xOf(0), yOf(values[0]))
        for (i in 1 until values.size) path.lineTo(xOf(i), yOf(values[i]))
        canvas.drawPath(path, paint)
    }

    private fun drawBands(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        fun yOf(v: Float) = top + h * (1f - v / 100f)

        data class Band(val lo: Float, val hi: Float, val argb: Int)
        val bands = listOf(
            Band(75f, 100f, Color.argb(28, 198, 40,  40)),   // red   — BURN
            Band(50f,  75f, Color.argb(22, 230, 74,  25)),   // orange — STRAINED
            Band(25f,  50f, Color.argb(20, 249, 168, 37)),   // amber  — WARMING
            Band( 0f,  25f, Color.argb(18, 67,  160, 71)),   // green  — STABLE
        )
        for (b in bands) {
            bandPaint.color = b.argb
            canvas.drawRect(left, yOf(b.hi), left + w, yOf(b.lo), bandPaint)
        }
    }

    private fun drawBadge(canvas: Canvas, label: String, color: Int, right: Float, top: Float) {
        badgeTextPaint.textSize = 6.5f * dp
        val tw = badgeTextPaint.measureText(label)
        val bw = tw + 8f * dp
        val bh = 13f * dp
        val bx = right - bw
        badgePaint.color = color
        canvas.drawRoundRect(RectF(bx, top, right, top + bh), 3f * dp, 3f * dp, badgePaint)
        canvas.drawText(label, bx + bw / 2f, top + bh * 0.70f, badgeTextPaint)
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float) {
        val lineLen = 12f * dp
        val gap     = 4f * dp
        var cx      = x

        data class LegendItem(val color: Int, val label: String)
        val items = listOf(
            LegendItem(COLOR_BURN,   "Burn"),
            LegendItem(COLOR_MIDDLE, "Middle"),
            LegendItem(COLOR_BUFFER, "Buffer"),
        )
        for (item in items) {
            legendLinePaint.color = item.color
            canvas.drawLine(cx, y, cx + lineLen, y, legendLinePaint)
            cx += lineLen + gap
            legendTextPaint.color = Color.parseColor("#616161")
            canvas.drawText(item.label, cx, y + 3f * dp, legendTextPaint)
            cx += legendTextPaint.measureText(item.label) + 10f * dp
        }
    }

    // ── Color / label helpers ─────────────────────────────────────────────────

    private fun bandColor(score: Float) = when {
        score >= 75f -> Color.parseColor("#C62828")
        score >= 50f -> Color.parseColor("#E64A19")
        score >= 25f -> Color.parseColor("#F9A825")
        else         -> Color.parseColor("#43A047")
    }

    private fun bandLabel(score: Float) = when {
        score >= 75f -> "BURN"
        score >= 50f -> "STRAINED"
        score >= 25f -> "WARMING"
        else         -> "STABLE"
    }
}
