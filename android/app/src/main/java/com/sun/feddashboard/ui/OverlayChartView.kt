package com.sun.feddashboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.sun.feddashboard.model.ChartPoint

/**
 * Two-line overlay chart:
 *  - Solid line  = regular/coincident composite (ISI or LSI)
 *  - Dashed line = leading composite (LIIMSI or LLMSI)
 *
 * No external charting library required.
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

    private val regularLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val leadingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f * dp, 4f * dp), 0f)
    }

    private val regularFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBBBBB")
        strokeWidth = 1f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * dp, 3f * dp), 0f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 9f * dp
        textAlign = Paint.Align.CENTER
    }

    private val latestLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * dp
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 13f * dp
        textAlign = Paint.Align.CENTER
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * dp
        textAlign = Paint.Align.LEFT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val hasRegular = regularPoints.isNotEmpty()
        val hasLeading = leadingPoints.isNotEmpty()

        if (!hasRegular && !hasLeading) {
            canvas.drawText("No data — tap ↻ to fetch", width / 2f, height / 2f, noDataPaint)
            return
        }

        val regularColor = regimeColor(regularRegime)
        val leadingColor = dimColor(regimeColor(leadingRegime))

        regularLinePaint.color = regularColor
        leadingLinePaint.color = leadingColor
        regularFillPaint.color = Color.argb(30, Color.red(regularColor), Color.green(regularColor), Color.blue(regularColor))

        val padL = 10f * dp
        val padR = 10f * dp
        val padT = 16f * dp   // space for legend
        val padB = 22f * dp   // space for x-axis labels

        val chartW = width  - padL - padR
        val chartH = height - padT - padB

        // Combine all values to find Y range
        val allValues = regularPoints.map { it.value } + leadingPoints.map { it.value }
        val minVal = minOf(allValues.minOrNull() ?: -0.5f, -0.3f)
        val maxVal = maxOf(allValues.maxOrNull() ?: 0.5f,   0.3f)
        val range = maxVal - minVal

        // Use a shared month axis: union of both series labels aligned by index
        // For simplicity, overlay both on a 0..1 normalized x-axis
        val rn = regularPoints.size.coerceAtLeast(1)
        val ln = leadingPoints.size.coerceAtLeast(1)
        fun xOfR(i: Int) = padL + i * chartW / (rn - 1).coerceAtLeast(1)
        fun xOfL(i: Int) = padL + i * chartW / (ln - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - (v - minVal) / range)

        val zeroY = yOf(0f).coerceIn(padT, padT + chartH)

        // Legend (top)
        drawLegend(canvas, padL, padT - 4f * dp, regularColor, leadingColor)

        // Zero reference line
        canvas.drawLine(padL, zeroY, padL + chartW, zeroY, zeroLinePaint)

        // Regular fill
        if (hasRegular && rn >= 2) {
            val fillPath = Path()
            fillPath.moveTo(xOfR(0), zeroY)
            fillPath.lineTo(xOfR(0), yOf(regularPoints[0].value))
            for (i in 1 until rn) fillPath.lineTo(xOfR(i), yOf(regularPoints[i].value))
            fillPath.lineTo(xOfR(rn - 1), zeroY)
            fillPath.close()
            canvas.drawPath(fillPath, regularFillPaint)
        }

        // Leading line (drawn first so regular is on top)
        if (hasLeading && ln >= 2) {
            val path = Path()
            path.moveTo(xOfL(0), yOf(leadingPoints[0].value))
            for (i in 1 until ln) path.lineTo(xOfL(i), yOf(leadingPoints[i].value))
            canvas.drawPath(path, leadingLinePaint)
        }

        // Regular line
        if (hasRegular && rn >= 2) {
            val path = Path()
            path.moveTo(xOfR(0), yOf(regularPoints[0].value))
            for (i in 1 until rn) path.lineTo(xOfR(i), yOf(regularPoints[i].value))
            canvas.drawPath(path, regularLinePaint)
        }

        // X-axis labels (from regular series if available, else leading)
        val labelPoints = if (hasRegular) regularPoints else leadingPoints
        val labelCount  = labelPoints.size
        val labelX: (Int) -> Float = if (hasRegular) { i -> xOfR(i) } else { i -> xOfL(i) }
        val labelY = padT + chartH + 15f * dp
        for (i in labelPoints.indices) {
            if (i == 0 || i == labelCount - 1 || i % 3 == 0) {
                canvas.drawText(labelPoints[i].monthLabel, labelX(i), labelY, labelPaint)
            }
        }

        // Latest value callouts
        if (hasRegular) {
            val last = regularPoints.last()
            val lx = xOfR(rn - 1)
            val ly = yOf(last.value)
            latestLabelPaint.color = regularColor
            val text = "%.2f".format(last.value)
            canvas.drawText(text, lx + 4 * dp, ly - 2 * dp, latestLabelPaint)
        }
        if (hasLeading) {
            val last = leadingPoints.last()
            val lx = xOfL(ln - 1)
            val ly = yOf(last.value)
            latestLabelPaint.color = leadingColor
            val text = "%.2f".format(last.value)
            canvas.drawText(text, lx + 4 * dp, ly + 12 * dp, latestLabelPaint)
        }
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, regularColor: Int, leadingColor: Int) {
        val lineLen = 18f * dp
        val gap = 6f * dp
        var cx = x

        // Solid line + label
        val solidPaint = Paint(regularLinePaint).apply { pathEffect = null }
        canvas.drawLine(cx, y, cx + lineLen, y, solidPaint)
        cx += lineLen + gap
        legendPaint.color = regularColor
        canvas.drawText("Regular", cx, y + 4f * dp, legendPaint)
        cx += legendPaint.measureText("Regular") + 16f * dp

        // Dashed line + label
        canvas.drawLine(cx, y, cx + lineLen, y, leadingLinePaint)
        cx += lineLen + gap
        legendPaint.color = leadingColor
        canvas.drawText("Leading", cx, y + 4f * dp, legendPaint)
    }

    /** Dim a color for the leading (secondary) line. */
    private fun dimColor(color: Int): Int {
        val r = (Color.red(color)   * 0.75f + 30).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * 0.75f + 30).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  * 0.75f + 30).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun regimeColor(r: String) = when (r) {
        "STRONG", "COOLING"       -> Color.parseColor("#66BB6A")
        "MODERATE", "ANCHORED"    -> Color.parseColor("#00BFA5")
        "SOFTENING", "RISING"     -> Color.parseColor("#FFC107")
        "WEAK", "ELEVATED"        -> Color.parseColor("#FF7043")
        "CRITICAL"                -> Color.parseColor("#EF5350")
        "DEFLATIONARY"            -> Color.parseColor("#5C6BC0")
        else                      -> Color.parseColor("#00BFA5")
    }
}
