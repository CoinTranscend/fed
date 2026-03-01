package com.sun.feddashboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sun.feddashboard.model.ChartPoint

/**
 * Professional two-line overlay chart with regime background bands.
 *
 * Visual design:
 *  - Subtle regime-colored background zones (green/teal/amber/orange/red)
 *  - Y-axis labels and horizontal gridlines at z-score thresholds
 *  - Solid line = coincident composite; dashed gray = leading composite
 *  - Year-boundary x-axis labels only
 *  - Regime badge pill in upper-right corner
 */
class OverlayChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var regularPoints: List<ChartPoint> = emptyList()
        set(value) { field = value; invalidate() }

    var leadingPoints: List<ChartPoint> = emptyList()
        set(value) { field = value; invalidate() }

    var regularRegime: String = "ANCHORED"
        set(value) { field = value; invalidate() }

    var leadingRegime: String = "ANCHORED"
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    // ── Paints ────────────────────────────────────────────────────────────────

    private val regularLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }

    private val leadingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#BDBDBD")
        strokeWidth = 1.5f * dp
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
        pathEffect  = DashPathEffect(floatArrayOf(7f * dp, 4f * dp), 0f)
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#BCBCBC")
        strokeWidth = 1f * dp
        style       = Paint.Style.STROKE
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#EBEBEB")
        strokeWidth = 0.7f * dp
        style       = Paint.Style.STROKE
    }

    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#9E9E9E")
        textSize = 7.5f * dp
        textAlign = Paint.Align.RIGHT
        typeface  = Typeface.MONOSPACE
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.parseColor("#9E9E9E")
        textSize = 7.5f * dp
        textAlign = Paint.Align.CENTER
    }

    private val calloutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize     = 9f * dp
        isFakeBoldText = true
        textAlign    = Paint.Align.LEFT
    }

    private val legendLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        strokeCap   = Paint.Cap.ROUND
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.5f * dp
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

    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#9E9E9E")
        textSize  = 13f * dp
        textAlign = Paint.Align.CENTER
    }

    // ── Layout constants ──────────────────────────────────────────────────────

    // padL must fit y-axis labels like "−1.5" (4 chars monospace + margin)
    private val PAD_L get() = 36f * dp
    private val PAD_R get() = 42f * dp   // callout value + badge room
    private val PAD_T get() = 22f * dp   // legend row
    private val PAD_B get() = 17f * dp   // x-axis labels

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hasRegular = regularPoints.isNotEmpty()
        val hasLeading = leadingPoints.isNotEmpty()

        if (!hasRegular && !hasLeading) {
            canvas.drawText("No data — tap ↻ to fetch", width / 2f, height / 2f, noDataPaint)
            return
        }

        val regularColor = regimeColor(regularRegime)
        regularLinePaint.color = regularColor

        val padL = PAD_L; val padR = PAD_R; val padT = PAD_T; val padB = PAD_B
        val chartW = width  - padL - padR
        val chartH = height - padT - padB

        val allValues = regularPoints.map { it.value } + leadingPoints.map { it.value }
        val minVal = minOf(allValues.minOrNull() ?: -0.5f, -1.4f)
        val maxVal = maxOf(allValues.maxOrNull() ?: 0.5f,   1.4f)
        val range  = (maxVal - minVal).coerceAtLeast(0.01f)

        val rn = regularPoints.size.coerceAtLeast(1)
        val ln = leadingPoints.size.coerceAtLeast(1)

        fun xOfR(i: Int) = padL + i * chartW / (rn - 1).coerceAtLeast(1)
        fun xOfL(i: Int) = padL + i * chartW / (ln - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - (v - minVal) / range)

        // 1. Regime background bands
        drawRegimeBands(canvas, padL, padT, chartW, chartH, minVal, maxVal)

        // 2. Gridlines + y-axis labels
        for (t in listOf(1.5f, 1.0f, 0.5f, 0f, -0.5f, -1.0f, -1.5f)) {
            if (t < minVal - 0.05f || t > maxVal + 0.05f) continue
            val y  = yOf(t).coerceIn(padT, padT + chartH)
            val lp = if (t == 0f) zeroLinePaint else gridLinePaint
            canvas.drawLine(padL, y, padL + chartW, y, lp)
            val lbl = when {
                t > 0f  -> "+%.1f".format(t)
                t == 0f -> " 0.0"
                else    -> "%.1f".format(t)
            }
            canvas.drawText(lbl, padL - 3f * dp, y + axisLabelPaint.textSize * 0.38f, axisLabelPaint)
        }

        // 3. Leading line (behind regular so regular reads on top)
        if (hasLeading && ln >= 2) {
            val path = Path()
            path.moveTo(xOfL(0), yOf(leadingPoints[0].value))
            for (i in 1 until ln) path.lineTo(xOfL(i), yOf(leadingPoints[i].value))
            canvas.drawPath(path, leadingLinePaint)
        }

        // 4. Regular (coincident) line
        if (hasRegular && rn >= 2) {
            val path = Path()
            path.moveTo(xOfR(0), yOf(regularPoints[0].value))
            for (i in 1 until rn) path.lineTo(xOfR(i), yOf(regularPoints[i].value))
            canvas.drawPath(path, regularLinePaint)
        }

        // 5. X-axis labels — year boundaries ("'22", "'23" …)
        val labelPts = if (hasRegular) regularPoints else leadingPoints
        val labelXFn: (Int) -> Float = if (hasRegular) { i -> xOfR(i) } else { i -> xOfL(i) }
        val labelY = padT + chartH + 13f * dp
        var lastYr = ""
        for (i in labelPts.indices) {
            val lbl = labelPts[i].monthLabel          // e.g. "Jan '24"
            val yr  = lbl.substringAfter("'", "")    // "24"
            val mon = lbl.substringBefore(" ")
            if (yr.isNotEmpty() && (mon == "Jan" || i == 0) && yr != lastYr) {
                canvas.drawText("'$yr", labelXFn(i), labelY, xLabelPaint)
                lastYr = yr
            }
        }

        // 6. Right-side latest-value callouts
        val calloutX = padL + chartW + 4f * dp
        if (hasRegular) {
            calloutPaint.color = regularColor
            val cy = yOf(regularPoints.last().value).coerceIn(padT + 9f * dp, padT + chartH - 3f * dp)
            canvas.drawText("%.2f".format(regularPoints.last().value), calloutX, cy, calloutPaint)
        }
        if (hasLeading) {
            calloutPaint.color = Color.parseColor("#9E9E9E")
            val cy = yOf(leadingPoints.last().value).coerceIn(padT + 20f * dp, padT + chartH + 8f * dp)
            canvas.drawText("%.2f".format(leadingPoints.last().value), calloutX, cy, calloutPaint)
        }

        // 7. Regime badge — top-right, inside right margin
        drawRegimeBadge(canvas, regularRegime, regularColor,
            right = (width - 2f * dp),
            top   = padT - 16f * dp)

        // 8. Legend — top-left (only when dual-line)
        if (hasLeading) {
            drawLegend(canvas, padL, 9f * dp, regularColor)
        }
    }

    // ── Regime bands ──────────────────────────────────────────────────────────

    private fun drawRegimeBands(
        canvas: Canvas,
        left: Float, top: Float, w: Float, h: Float,
        minVal: Float, maxVal: Float,
    ) {
        val range = (maxVal - minVal).coerceAtLeast(0.01f)
        fun yOf(v: Float) = top + h * (1f - (v - minVal) / range)

        data class Band(val lo: Float, val hi: Float, val argb: Int)

        val bands = listOf(
            Band(0.5f,    maxVal,  Color.argb(18, 67,  160, 71)),   // green
            Band(0.0f,    0.5f,    Color.argb(18, 0,   137, 123)),  // teal
            Band(-0.5f,   0.0f,    Color.argb(18, 249, 168, 37)),   // amber
            Band(-1.0f,  -0.5f,    Color.argb(20, 230, 74,  25)),   // deep orange
            Band(minVal, -1.0f,    Color.argb(22, 198, 40,  40)),   // red
        )

        for (b in bands) {
            val lo = b.lo.coerceAtLeast(minVal)
            val hi = b.hi.coerceAtMost(maxVal)
            if (lo >= hi) continue
            val yTop = yOf(hi).coerceIn(top, top + h)
            val yBot = yOf(lo).coerceIn(top, top + h)
            if (yBot <= yTop) continue
            bandFillPaint.color = b.argb
            canvas.drawRect(left, yTop, left + w, yBot, bandFillPaint)
        }
    }

    // ── Regime badge ─────────────────────────────────────────────────────────

    private fun drawRegimeBadge(canvas: Canvas, regime: String, color: Int, right: Float, top: Float) {
        badgeTextPaint.textSize = 6.5f * dp
        val tw  = badgeTextPaint.measureText(regime)
        val bw  = tw + 8f * dp
        val bh  = 13f * dp
        val bx  = right - bw
        val by  = top
        badgePaint.color = color
        canvas.drawRoundRect(RectF(bx, by, right, by + bh), 3f * dp, 3f * dp, badgePaint)
        canvas.drawText(regime, bx + bw / 2f, by + bh * 0.70f, badgeTextPaint)
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, regularColor: Int) {
        val lineLen = 12f * dp
        val gap     = 4f * dp
        var cx      = x

        legendLinePaint.color     = regularColor
        legendLinePaint.pathEffect = null
        canvas.drawLine(cx, y, cx + lineLen, y, legendLinePaint)
        cx += lineLen + gap
        legendTextPaint.color = Color.parseColor("#616161")
        canvas.drawText("Coincident", cx, y + 3f * dp, legendTextPaint)
        cx += legendTextPaint.measureText("Coincident") + 12f * dp

        legendLinePaint.color     = Color.parseColor("#BDBDBD")
        legendLinePaint.pathEffect = DashPathEffect(floatArrayOf(5f * dp, 3f * dp), 0f)
        canvas.drawLine(cx, y, cx + lineLen, y, legendLinePaint)
        cx += lineLen + gap
        legendTextPaint.color = Color.parseColor("#9E9E9E")
        canvas.drawText("Leading", cx, y + 3f * dp, legendTextPaint)
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    fun regimeColor(r: String) = when (r) {
        "STRONG", "COOLING", "LOW RISK"  -> Color.parseColor("#43A047")
        "MODERATE", "ANCHORED", "STABLE" -> Color.parseColor("#00897B")
        "SOFTENING", "RISING", "CAUTION" -> Color.parseColor("#F9A825")
        "WEAK", "ELEVATED", "WARNING"    -> Color.parseColor("#E64A19")
        "CRITICAL"                        -> Color.parseColor("#C62828")
        "DEFLATIONARY"                    -> Color.parseColor("#4527A0")
        else                              -> Color.parseColor("#00897B")
    }
}
