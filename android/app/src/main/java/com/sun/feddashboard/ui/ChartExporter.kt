package com.sun.feddashboard.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sun.feddashboard.model.ChartPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Renders a high-resolution chart to a Bitmap and saves it to Downloads.
 * Must be called from an IO dispatcher.
 */
object ChartExporter {

    private const val HD_W = 2000
    private const val HD_H = 900

    /**
     * Export a single overlay chart (one index pair) to Downloads.
     * Returns the saved file path on success, null on failure.
     */
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
        canvas.drawColor(Color.parseColor("#F5F5F7"))

        val scale = HD_W / 420f   // 420dp reference width

        drawRow(
            canvas, title,
            regular, leading,
            regRegime, leadRegime,
            regScore, leadScore,
            height = HD_H.toFloat(),
            width  = HD_W.toFloat(),
            scale  = scale,
        )

        return saveToDownloads(context, bitmap, fileName)
    }

    // ─── Drawing ───────────────────────────────────────────────────────────────

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
        val leadingColor = dimColor(colorFromRegime(leadRegime))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1E")
            textSize = 14f * scale
            isFakeBoldText = true
        }
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6E6E73")
            textSize = 11f * scale
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9E9E9E")
            textSize = 9f * scale
            textAlign = Paint.Align.CENTER
        }
        val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCCCCC")
            strokeWidth = 1.5f * scale
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f * scale, 3f * scale), 0f)
        }
        val regularLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = regularColor
            strokeWidth = 3.5f * scale
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val leadingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = leadingColor
            strokeWidth = 2.5f * scale
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(9f * scale, 5f * scale), 0f)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(35, Color.red(regularColor), Color.green(regularColor), Color.blue(regularColor))
            style = Paint.Style.FILL
        }
        val calloutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * scale
            isFakeBoldText = true
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * scale
            color = Color.parseColor("#6E6E73")
        }

        val padL = 20f * scale
        val padR = 80f * scale   // generous right pad so callout labels never clip
        val padT = 60f * scale
        val padB = 50f * scale
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

        val minVal = minOf(allValues.min(), -0.3f)
        val maxVal = maxOf(allValues.max(),  0.3f)
        val range  = (maxVal - minVal).coerceAtLeast(0.01f)

        val rn = regular.size.coerceAtLeast(1)
        val ln = leading.size.coerceAtLeast(1)

        fun xOfR(i: Int) = padL + i * chartW / (rn - 1).coerceAtLeast(1)
        fun xOfL(i: Int) = padL + i * chartW / (ln - 1).coerceAtLeast(1)
        fun yOf(v: Float) = padT + chartH * (1f - (v - minVal) / range)

        val zeroY = yOf(0f).coerceIn(padT, padT + chartH)
        canvas.drawLine(padL, zeroY, padL + chartW, zeroY, zeroLinePaint)

        // Fill
        if (regular.size >= 2) {
            val fp = Path().apply {
                moveTo(xOfR(0), zeroY)
                lineTo(xOfR(0), yOf(regular[0].value))
                for (i in 1 until rn) lineTo(xOfR(i), yOf(regular[i].value))
                lineTo(xOfR(rn - 1), zeroY)
                close()
            }
            canvas.drawPath(fp, fillPaint)
        }

        // Leading line
        if (leading.size >= 2) {
            val p = Path().apply {
                moveTo(xOfL(0), yOf(leading[0].value))
                for (i in 1 until ln) lineTo(xOfL(i), yOf(leading[i].value))
            }
            canvas.drawPath(p, leadingLinePaint)
        }

        // Regular line
        if (regular.size >= 2) {
            val p = Path().apply {
                moveTo(xOfR(0), yOf(regular[0].value))
                for (i in 1 until rn) lineTo(xOfR(i), yOf(regular[i].value))
            }
            canvas.drawPath(p, regularLinePaint)
        }

        // X-axis labels
        val labelPoints = regular.ifEmpty { leading }
        val labelXFn: (Int) -> Float = if (regular.isNotEmpty()) ::xOfR else ::xOfL
        val labelY = padT + chartH + 30f * scale
        for (i in labelPoints.indices) {
            if (i == 0 || i == labelPoints.size - 1 || i % 3 == 0) {
                canvas.drawText(labelPoints[i].monthLabel, labelXFn(i), labelY, labelPaint)
            }
        }

        // Latest value callouts — always drawn INSIDE padR zone to the right of last point
        val calloutX = padL + chartW + 6f * scale
        if (regular.isNotEmpty()) {
            calloutPaint.color = regularColor
            val calloutY = yOf(regular.last().value).coerceIn(padT + 14f*scale, padT + chartH)
            canvas.drawText("%.2f".format(regular.last().value), calloutX, calloutY, calloutPaint)
        }
        if (leading.isNotEmpty()) {
            calloutPaint.color = leadingColor
            val calloutY = yOf(leading.last().value).coerceIn(padT + 30f*scale, padT + chartH + 14f*scale)
            canvas.drawText("%.2f".format(leading.last().value), calloutX, calloutY, calloutPaint)
        }

        // Legend
        val legY = padT + chartH + 44f * scale
        var legX = padL
        val lineLen = 20f * scale

        val solidP = Paint(regularLinePaint).apply { pathEffect = null }
        canvas.drawLine(legX, legY, legX + lineLen, legY, solidP)
        legX += lineLen + 6f * scale
        legendPaint.color = regularColor
        canvas.drawText(" Coincident (regular)", legX, legY + 4f * scale, legendPaint)
        legX += legendPaint.measureText(" Coincident (regular)") + 20f * scale

        canvas.drawLine(legX, legY, legX + lineLen, legY, leadingLinePaint)
        legX += lineLen + 6f * scale
        legendPaint.color = leadingColor
        canvas.drawText(" Leading (forward)", legX, legY + 4f * scale, legendPaint)
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

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

    // ─── Color helpers ────────────────────────────────────────────────────────

    fun colorFromRegime(r: String): Int = when (r) {
        "STRONG", "COOLING", "LOW RISK"  -> Color.parseColor("#66BB6A")
        "MODERATE", "ANCHORED", "STABLE" -> Color.parseColor("#00BFA5")
        "SOFTENING", "RISING", "CAUTION" -> Color.parseColor("#FFC107")
        "WEAK", "ELEVATED", "WARNING"    -> Color.parseColor("#FF7043")
        "CRITICAL"                        -> Color.parseColor("#EF5350")
        "DEFLATIONARY"                    -> Color.parseColor("#5C6BC0")
        else                              -> Color.parseColor("#00BFA5")
    }

    private fun dimColor(color: Int): Int {
        val r = (Color.red(color)   * 0.7f + 40).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * 0.7f + 40).toInt().coerceIn(0, 255)
        val b = (Color.blue(color)  * 0.7f + 40).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
