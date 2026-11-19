package com.example.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Draws a subtle screen-pixel calibration grid onto a copy of the screenshot
 * sent to AI models for games/complex canvases so the model reads exact (x, y)
 * pixel coordinates instead of guessing visually.
 */
object VisualGridAnnotator {

    fun createAnnotatedCopy(
        source: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 0, 229, 255)
            strokeWidth = 1.4f
        }
        val labelBgPaint = Paint().apply {
            color = Color.argb(165, 10, 14, 20)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 0, 245, 212)
            textSize = (copy.width * 0.022f).coerceIn(14f, 24f)
            isFakeBoldText = true
        }

        val cols = 5
        val rows = 8

        for (i in 1 until cols) {
            val bx = copy.width * (i.toFloat() / cols)
            val screenX = (screenWidth * (i.toFloat() / cols)).toInt()
            canvas.drawLine(bx, 0f, bx, copy.height.toFloat(), linePaint)
            val label = "x$screenX"
            val tw = textPaint.measureText(label)
            canvas.drawRect(bx - tw / 2f - 4f, 4f, bx + tw / 2f + 4f, textPaint.textSize + 8f, labelBgPaint)
            canvas.drawText(label, bx - tw / 2f, textPaint.textSize + 2f, textPaint)
        }

        for (j in 1 until rows) {
            val by = copy.height * (j.toFloat() / rows)
            val screenY = (screenHeight * (j.toFloat() / rows)).toInt()
            canvas.drawLine(0f, by, copy.width.toFloat(), by, linePaint)
            val label = "y$screenY"
            val tw = textPaint.measureText(label)
            canvas.drawRect(4f, by - textPaint.textSize - 2f, tw + 12f, by + 4f, labelBgPaint)
            canvas.drawText(label, 8f, by - 2f, textPaint)
        }

        return copy
    }
}
