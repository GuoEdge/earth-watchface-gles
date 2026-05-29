package com.earthwatch.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLUtils
import kotlin.math.ceil

class TextTextureCache {

    private data class Entry(
        val texId: Int,
        val width: Int,
        val height: Int,
        val pad: Int,
        var lastText: String,
        var lastPaintFingerprint: String
    )

    private val cache = HashMap<String, Entry>()

    data class TexResult(val texId: Int, val w: Int, val h: Int, val pad: Int)

    fun getOrUpdate(key: String, paint: Paint, text: String): TexResult? {
        val paintFp = paintFingerprint(paint)
        val existing = cache[key]
        if (existing != null && existing.lastText == text && existing.lastPaintFingerprint == paintFp) {
            return TexResult(existing.texId, existing.width, existing.height, existing.pad)
        }

        if (text.isEmpty()) return null

        val shadowPad = (maxOf(paint.shadowLayerRadius + kotlin.math.abs(paint.shadowLayerDx),
            paint.shadowLayerRadius + kotlin.math.abs(paint.shadowLayerDy))).toInt().coerceAtLeast(2)
        val w = ceil(paint.measureText(text)).toInt() + shadowPad * 2
        val fm = paint.fontMetrics
        val h = ceil(fm.descent - fm.ascent).toInt() + shadowPad * 2
        if (w <= 0 || h <= 0) return null

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val savedAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        c.drawText(text, shadowPad.toFloat(), shadowPad.toFloat() - fm.ascent, paint)
        paint.textAlign = savedAlign

        val texId: Int
        if (existing != null) {
            texId = existing.texId
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        } else {
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }

        bmp.recycle()
        val entry = Entry(texId, w, h, shadowPad, text, paintFp)
        cache[key] = entry
        return TexResult(texId, w, h, shadowPad)
    }

    private fun paintFingerprint(p: Paint): String {
        return "${p.color},${p.textSize},${p.shadowLayerRadius},${p.shadowLayerDx},${p.shadowLayerDy},${p.shadowLayerColor},${p.typeface}"
    }

    fun release() {
        for (entry in cache.values) {
            GLES20.glDeleteTextures(1, intArrayOf(entry.texId), 0)
        }
        cache.clear()
    }
}
