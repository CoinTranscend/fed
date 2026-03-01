package com.sun.feddashboard.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sun.feddashboard.model.ChartPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Renders a high-resolution chart to a Bitmap and saves it to Downloads.
 * Visual design matches OverlayChartView: regime background bands, y-axis
 * labels, threshold gridlines, clean lines with no area fill.
 * Must be called from an IO dispatcher.
 */
object ChartExporter {

    private const val HD_W = 2000
    private const val HD_H = 900

    fun exportSingle(
        context: Context,
        title: String,
        regular: List<ChartPoint>,
        leading: List<ChartPoint>,
        regRegime: String,
        leadRegime: String,
        regScore: Float?,
        leadScore: Float?,
        fileName: String,
    ): String? {
        val bitmap = Bitmap.createBitmap(HD_W, HD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val scale = HD_W / 420f

        drawRow(canvas, title, regular, leading, regRegime, leadRegime, regScore, leadScore,
            height = HD_H.toFloat(), width = HD_W.toFloat(), scale = scale)

        return saveToDownloads(context, bitmap, fileName)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    fun drawRow(
        canvas: Canvas,
        title: String,
        regular: List<ChartPoint>,
        leading: List<ChartPoint>,
        regRegime: String,
        leadRegime: String,
        regScore: Float?,
        leadScore: Float?,
        height: Float,
        width: Float,
        scale: Float,
    ) {
        val regularColor = colorFromRegime(regRegime)

        // ── Text paints ──────────────────────────────────────────────────────
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = Color.parseColor("#1C1C1E")
            textSize       = 13f * scale
            isFakeBoldText = true
        }
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.parseColor("#6E6E73")
            textSize = 10f * scale
        }
        val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.parseColor("#9E9E9E")
            textSize  = 8f * scale
            textAlign = Paint.Align.RIGHT
            typeface  = Typeface.MONOSPACE
        }
        val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.parseColor("#9E9E9E")
            textSize  = 8f * scale
            textAlign = Paint.Align.CENTER
        }
        val calloutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize       = 11f * scale
            isFakeBoldText = true
        }
        val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * scale
            color    = Color.parseColor("#757575")
        }
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = Color.WHITE
            textSize       = 7.5f * scale
            isFakeBoldText = true
            textAlign      = Paint.Align.CENTER
        }

        // ── Line paints ──────────────────────────────────────────────────────
        val regularLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = regularColor
            strokeWidth = 3f * scale
            style       = Paint.Style.STROKE
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
        }
        val leadingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.parseColor("#BDBDBD")
            strokeWidth = 2f * scale
            style       = Paint.Style.STROKE
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
            pathEffect  = DashPathEffect(floatArrayOf(9f * scale, 5f * scale), 0f)
        }
        val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.parseColor("#BCBCBC")
            strokeWidth = 1.2f * scale
            style       = Paint.Style.STROKE
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.parseColor("#EBEBEB")
            strokeWidth = 0.8f * scale
            style       = Paint.Style.STROKE
        }
        val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // ── Layout ───────────────────────────────────────────────────────────
        val padL = 52f * scale
        val padR = 82f * scale
        val padT = 60f * scale
        val padB = 48f * scale
        val chartW = width  - padL - padR
        val chartH = height - padT - padB

        // Title
        canvas.drawText(title, padL, 24f * scale, titlePaint)

        // Score sub-header
        if (regScore != null || leadScore != null) {
            val txt = buildString {
                if (regScore  != null) append("$regRegime: ${"%.2f".format(regScore)}   ")
                if (leadScore != null) append("Leading: ${"%.2f".format(leadScore)}")
            }
            canvas.drawText(txt, padL, 42f * scale, scorePaint)
        }

        val allValues = regular.map { it.value } + leading.map { it.value }
        if (allValues.isEmpty()) return

        val minVal = minOf(allValues.min(), -1.4f)
        val maxVal = maxOf(allValues.max(),  1.4f)
        val range  = (maxVal - minVal).coerceAtLeast(0.01f)

        val rn = regular.size.coerceAtLeast(1)
        val ln = leading.size.coerceAtLeast(1)

        fun xOfR(i: Int) = padL + i * chartW / (rn - 1).coerceAtLeast(1)
        fun xOfL(i: Int) = padL + i * chartW / (ln - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - (v - minVal) / range)

        // 1. Regime background bands
        data class Band(val lo: Float, val hi: Float, val argb: Int)
        val bands = listOf(
            Band(0.5f,   maxVal,  Color.argb(18, 67,  160, 71)),
            Band(0.0f,   0.5f,   Color.argb(18, 0,   137, 123)),
            Band(-0.5f,  0.0f,   Color.argb(18, 249, 168, 37)),
            Band(-1.0f, -0.5f,   Color.argb(20, 230, 74,  25)),
            Band(minVal, -1.0f,  Color.argb(22, 198, 40,  40)),
        )
        for (b in bands) {
            val lo = b.lo.coerceAtLeast(minVal)
            val hi = b.hi.coerceAtMost(maxVal)
            if (lo >= hi) continue
            val yTop = yOf(hi).coerceIn(padT, padT + chartH)
            val yBot = yOf(lo).coerceIn(padT, padT + chartH)
            if (yBot <= yTop) continue
            bandFillPaint.color = b.argb
            canvas.drawRect(padL, yTop, padL + chartW, yBot, bandFillPaint)
        }

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
            canvas.drawText(lbl, padL - 4f * scale, y + axisLabelPaint.textSize * 0.38f, axisLabelPaint)
        }

        // 3. Leading line
        if (leading.size >= 2) {
            val p = Path()
            p.moveTo(xOfL(0), yOf(leading[0].value))
            for (i in 1 until ln) p.lineTo(xOfL(i), yOf(leading[i].value))
            canvas.drawPath(p, leadingLinePaint)
        }

        // 4. Regular line
        if (regular.size >= 2) {
            val p = Path()
            p.moveTo(xOfR(0), yOf(regular[0].value))
            for (i in 1 until rn) p.lineTo(xOfR(i), yOf(regular[i].value))
            canvas.drawPath(p, regularLinePaint)
        }

        // 5. X-axis year labels
        val labelPts = regular.ifEmpty { leading }
        val labelXFn: (Int) -> Float = if (regular.isNotEmpty()) ::xOfR else ::xOfL
        val labelY = padT + chartH + 22f * scale
        var lastYr = ""
        for (i in labelPts.indices) {
            val lbl = labelPts[i].monthLabel
            val yr  = lbl.substringAfter("'", "")
            val mon = lbl.substringBefore(" ")
            if (yr.isNotEmpty() && (mon == "Jan" || i == 0) && yr != lastYr) {
                canvas.drawText("'$yr", labelXFn(i), labelY, xLabelPaint)
                lastYr = yr
            }
        }

        // 6. Right-side callouts
        val calloutX = padL + chartW + 6f * scale
        if (regular.isNotEmpty()) {
            calloutPaint.color = regularColor
            val cy = yOf(regular.last().value).coerceIn(padT + 12f * scale, padT + chartH - 4f * scale)
            canvas.drawText("%.2f".format(regular.last().value), calloutX, cy, calloutPaint)
        }
        if (leading.isNotEmpty()) {
            calloutPaint.color = Color.parseColor("#9E9E9E")
            val cy = yOf(leading.last().value).coerceIn(padT + 26f * scale, padT + chartH + 10f * scale)
            canvas.drawText("%.2f".format(leading.last().value), calloutX, cy, calloutPaint)
        }

        // 7. Regime badge (top-right, inside right padding)
        badgeTextPaint.textSize = 7.5f * scale
        val tw  = badgeTextPaint.measureText(regRegime)
        val bw  = tw + 9f * scale
        val bh  = 16f * scale
        val bx  = width - bw - 3f * scale
        val by  = padT - bh - 4f * scale
        badgePaint.color = regularColor
        canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 4f * scale, 4f * scale, badgePaint)
        canvas.drawText(regRegime, bx + bw / 2f, by + bh * 0.70f, badgeTextPaint)

        // 8. Legend
        val legY  = padT + chartH + 36f * scale
        var legX  = padL
        val lineLen = 18f * scale

        val solidPaint = Paint(regularLinePaint).apply { pathEffect = null }
        canvas.drawLine(legX, legY, legX + lineLen, legY, solidPaint)
        legX += lineLen + 5f * scale
        legendTextPaint.color = colorFromRegime(regRegime)
        canvas.drawText("Coincident", legX, legY + 4f * scale, legendTextPaint)
        legX += legendTextPaint.measureText("Coincident") + 20f * scale

        if (leading.isNotEmpty()) {
            canvas.drawLine(legX, legY, legX + lineLen, legY, leadingLinePaint)
            legX += lineLen + 5f * scale
            legendTextPaint.color = Color.parseColor("#9E9E9E")
            canvas.drawText("Leading", legX, legY + 4f * scale, legendTextPaint)
        }
    }

    // ── 30-year history export ────────────────────────────────────────────────

    /**
     * Exports a full-history RRI chart (~30 years) to Downloads.
     * Includes NBER recession shading for visual calibration.
     *
     * @param history List of (YYYY-MM, composite score) from RecessionEngine.computeHistory()
     */
    fun exportLongHistory(
        context: Context,
        title: String,
        history: List<Pair<String, Float>>,
        regime: String,
        fileName: String,
    ): String? {
        if (history.isEmpty()) return null

        val W = 2400; val H = 900
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val scale = W / 480f   // 480dp reference width (wider for 30 years)
        val color = colorFromRegime(regime)

        // ── Paints ────────────────────────────────────────────────────────────
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color    = Color.parseColor("#1C1C1E")
            textSize      = 13f * scale
            isFakeBoldText = true
        }
        val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#9E9E9E")
            textSize   = 7.5f * scale
            textAlign  = Paint.Align.RIGHT
            typeface   = Typeface.MONOSPACE
        }
        val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#9E9E9E")
            textSize   = 7.5f * scale
            textAlign  = Paint.Align.CENTER
        }
        val recessionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(35, 120, 120, 120)
            style      = Paint.Style.FILL
        }
        val recessionLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#AAAAAA")
            textSize   = 6.5f * scale
            textAlign  = Paint.Align.CENTER
        }
        val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color  = Color.parseColor("#BCBCBC")
            strokeWidth = 1f * scale
            style       = Paint.Style.STROKE
        }
        val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color  = Color.parseColor("#EBEBEB")
            strokeWidth = 0.7f * scale
            style       = Paint.Style.STROKE
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color  = color
            strokeWidth = 2f * scale
            style       = Paint.Style.STROKE
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
        }
        val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // ── Layout ────────────────────────────────────────────────────────────
        val padL = 50f * scale
        val padR = 24f * scale
        val padT = 44f * scale
        val padB = 32f * scale
        val chartW = W - padL - padR
        val chartH = H - padT - padB

        val values = history.map { it.second }
        val minVal = minOf(values.min(), -1.4f)
        val maxVal = maxOf(values.max(),  1.4f)
        val range  = (maxVal - minVal).coerceAtLeast(0.01f)

        val n = history.size.coerceAtLeast(1)
        fun xOf(i: Int)  = padL + i * chartW / (n - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - (v - minVal) / range)

        // Title
        canvas.drawText(title, padL, 26f * scale, titlePaint)

        // 1. Regime background bands
        data class Band(val lo: Float, val hi: Float, val argb: Int)
        val bands = listOf(
            Band(0.5f,   maxVal,  Color.argb(15, 67,  160, 71)),
            Band(0.0f,   0.5f,   Color.argb(15, 0,   137, 123)),
            Band(-0.5f,  0.0f,   Color.argb(15, 249, 168, 37)),
            Band(-1.0f, -0.5f,   Color.argb(18, 230, 74,  25)),
            Band(minVal, -1.0f,  Color.argb(20, 198, 40,  40)),
        )
        for (b in bands) {
            val lo = b.lo.coerceAtLeast(minVal); val hi = b.hi.coerceAtMost(maxVal)
            if (lo >= hi) continue
            val yTop = yOf(hi).coerceIn(padT, padT + chartH)
            val yBot = yOf(lo).coerceIn(padT, padT + chartH)
            if (yBot <= yTop) continue
            bandFillPaint.color = b.argb
            canvas.drawRect(padL, yTop, padL + chartW, yBot, bandFillPaint)
        }

        // 2. NBER recession shading (gray vertical bands)
        // Source: NBER Business Cycle Dating Committee
        val nberRecessions = listOf(
            "1990-07" to "1991-03" to "GFC'90",
            "2001-03" to "2001-11" to "Dot-com",
            "2007-12" to "2009-06" to "GFC",
            "2020-02" to "2020-04" to "COVID",
        )
        for ((period, label) in nberRecessions) {
            val (start, end) = period
            val startIdx = history.indexOfFirst { it.first >= start }
            val endIdx   = history.indexOfLast  { it.first <= end }
            if (startIdx < 0 || endIdx < startIdx) continue
            val rx = xOf(startIdx)
            val rw = (xOf(endIdx) - rx).coerceAtLeast(1f)
            canvas.drawRect(rx, padT, rx + rw, padT + chartH, recessionPaint)
            canvas.drawText(label, rx + rw / 2f, padT + 8f * scale, recessionLabelPaint)
        }

        // 3. Gridlines + y-axis labels
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
            canvas.drawText(lbl, padL - 3f * scale, y + axisLabelPaint.textSize * 0.38f, axisLabelPaint)
        }

        // 4. RRI line
        if (n >= 2) {
            val path = Path()
            path.moveTo(xOf(0), yOf(history[0].second))
            for (i in 1 until n) path.lineTo(xOf(i), yOf(history[i].second))
            canvas.drawPath(path, linePaint)
        }

        // 5. X-axis: every 5 years + endpoints
        var lastYr = ""
        for (i in history.indices) {
            val yyyyMM = history[i].first
            val yr = yyyyMM.substringBefore("-")
            val mo = yyyyMM.substringAfter("-").toIntOrNull() ?: 0
            val show = (mo == 1 && (yr.toInt() % 5 == 0)) || i == 0 || i == n - 1
            if (show && yr != lastYr) {
                canvas.drawText(yr, xOf(i), padT + chartH + 16f * scale, xLabelPaint)
                lastYr = yr
            }
        }

        // 6. Legend note
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#9E9E9E")
            textSize   = 7f * scale
        }
        val note = "Shaded = NBER recessions  ·  RRI: 8-series z-score composite  ·  Regime bands: ≥+0.5 LOW RISK → < −1.0 CRITICAL"
        canvas.drawText(note, padL, padT + chartH + 28f * scale, notePaint)

        return saveToDownloads(context, bitmap, fileName)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveToDownloads(context: Context, bitmap: Bitmap, fileName: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                "Downloads/$fileName"
            } catch (e: IOException) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            try {
                FileOutputStream(File(dir, fileName)).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                "Downloads/$fileName"
            } catch (e: IOException) {
                null
            }
        }
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    fun colorFromRegime(r: String): Int = when (r) {
        "STRONG", "COOLING", "LOW RISK"  -> Color.parseColor("#43A047")
        "MODERATE", "ANCHORED", "STABLE" -> Color.parseColor("#00897B")
        "SOFTENING", "RISING", "CAUTION" -> Color.parseColor("#F9A825")
        "WEAK", "ELEVATED", "WARNING"    -> Color.parseColor("#E64A19")
        "CRITICAL"                        -> Color.parseColor("#C62828")
        "DEFLATIONARY"                    -> Color.parseColor("#4527A0")
        else                              -> Color.parseColor("#00897B")
    }
}
