package com.earthwatch.face

import android.content.res.Resources
import android.graphics.*
import androidx.wear.watchface.CanvasComplication
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.complications.data.*
import java.time.ZonedDateTime

/** Renders [ComplicationData] for each [ComplicationSlot]. */
class EarthCanvasComplication : CanvasComplication {

    private var data: ComplicationData = EmptyComplicationData()

    // 主题色，由 EarthRenderer 更新
    var textColor: Int = Color.WHITE
    var titleColor: Int = 0xFFC8D8FF.toInt()
    var accentColor: Int = 0xFF42A5F5.toInt()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        renderParameters: RenderParameters,
        slotId: Int
    ) {
        val instant = zonedDateTime.toInstant()
        val resources = Resources.getSystem()

        when (data) {
            is ShortTextComplicationData -> {
                val d = data as ShortTextComplicationData
                val text = d.text.getTextAt(resources, instant)
                val title = d.title?.getTextAt(resources, instant)
                drawText(canvas, bounds, text, title)
            }
            is LongTextComplicationData -> {
                val d = data as LongTextComplicationData
                val text = d.text.getTextAt(resources, instant)
                val title = d.title?.getTextAt(resources, instant)
                drawText(canvas, bounds, text, title)
            }
            is RangedValueComplicationData -> {
                val d = data as RangedValueComplicationData
                val text = d.text?.getTextAt(resources, instant)
                val title = d.title?.getTextAt(resources, instant)
                drawRanged(canvas, bounds, d.value, d.min, d.max, text, title)
            }
            else -> {}
        }
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    override fun drawHighlight(
        canvas: Canvas,
        bounds: Rect,
        boundsType: Int,
        zonedDateTime: ZonedDateTime,
        color: Int
    ) {
        highlightPaint.color = color
        canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), 8f, 8f, highlightPaint)
    }

    override fun getData(): ComplicationData = data

    override fun loadData(complicationData: ComplicationData, loadDrawablesAsynchronous: Boolean) {
        data = complicationData
    }

    override fun onRendererCreated(renderer: Renderer) {}

    // ── helpers ──

    private fun drawText(c: Canvas, bounds: Rect, text: CharSequence, title: CharSequence?) {
        val cx = bounds.exactCenterX()
        if (title != null) {
            titlePaint.textSize = bounds.height() * 0.30f
            titlePaint.color = titleColor
            c.drawText(title.toString(), cx, bounds.top + titlePaint.textSize + 2f, titlePaint)
            textPaint.textSize = bounds.height() * 0.40f
            textPaint.color = textColor
            c.drawText(text.toString(), cx, bounds.bottom - 4f, textPaint)
        } else {
            textPaint.textSize = bounds.height() * 0.45f
            textPaint.color = textColor
            c.drawText(text.toString(), cx, bounds.exactCenterY() + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawRanged(
        c: Canvas, bounds: Rect, value: Float, min: Float, max: Float,
        text: CharSequence?, title: CharSequence?
    ) {
        val range = max - min
        val pct = if (range > 0f) ((value - min) / range).coerceIn(0f, 1f) else 0f
        val cx = bounds.exactCenterX()

        if (title != null) {
            titlePaint.textSize = bounds.height() * 0.26f
            titlePaint.color = titleColor
            c.drawText(title.toString(), cx, bounds.top + titlePaint.textSize + 2f, titlePaint)
        }

        if (text != null) {
            textPaint.textSize = bounds.height() * 0.38f
            textPaint.color = textColor
            c.drawText(text.toString(), cx, bounds.bottom - bounds.height() * 0.38f, textPaint)
        }

        val barY = bounds.bottom - 4f
        val barL = bounds.left + 6f
        val barR = bounds.right - 6f
        arcPaint.strokeWidth = 3f
        arcPaint.color = 0x33FFFFFF.toInt()
        c.drawLine(barL, barY, barR, barY, arcPaint)
        arcPaint.color = accentColor
        c.drawLine(barL, barY, barL + (barR - barL) * pct, barY, arcPaint)
    }
}
