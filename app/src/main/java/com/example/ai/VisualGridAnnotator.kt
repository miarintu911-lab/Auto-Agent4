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

    /**
     * Draws AppAgent / DroidRun-style Set-of-Mark (SoM) numbered bounding boxes over
     * interactive UI elements onto a copy of the screenshot so VLMs accurately ground
     * exact buttons and text fields without coordinate drift.
     */
    fun createSetOfMarkCopy(
        source: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        nodes: List<com.example.agent.ScreenNode>
    ): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        if (nodes.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return copy

        val canvas = Canvas(copy)
        val scaleX = copy.width.toFloat() / screenWidth.toFloat()
        val scaleY = copy.height.toFloat() / screenHeight.toFloat()

        val palette = intArrayOf(
            Color.rgb(0, 229, 255),
            Color.rgb(255, 193, 7),
            Color.rgb(255, 82, 162),
            Color.rgb(124, 179, 255)
        )
        val editableColor = Color.rgb(0, 230, 118)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (copy.width * 0.003f).coerceIn(1.8f, 3.5f)
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (copy.width * 0.024f).coerceIn(13f, 22f)
            isFakeBoldText = true
        }

        val interactiveNodes = nodes.filter {
            it.isClickable || it.isEditable || it.isChecked
        }.take(60)

        interactiveNodes.forEachIndexed { idx, node ->
            val left = (node.bounds.left * scaleX).coerceIn(1f, copy.width - 2f)
            val top = (node.bounds.top * scaleY).coerceIn(1f, copy.height - 2f)
            val right = (node.bounds.right * scaleX).coerceIn(left + 4f, copy.width - 1f)
            val bottom = (node.bounds.bottom * scaleY).coerceIn(top + 4f, copy.height - 1f)

            val boxColor = if (node.isEditable) editableColor else palette[idx % palette.size]
            boxPaint.color = boxColor
            tagBgPaint.color = boxColor

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val label = "${node.id}"
            val tw = tagTextPaint.measureText(label)
            val pillW = tw + 8f
            val pillH = tagTextPaint.textSize + 5f
            val pillLeft = left.coerceAtMost((copy.width - pillW - 1f).coerceAtLeast(1f))
            val pillTop = (top - pillH * 0.35f).coerceIn(1f, (copy.height - pillH - 1f).coerceAtLeast(1f))

            canvas.drawRect(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH, tagBgPaint)
            canvas.drawText(label, pillLeft + 4f, pillTop + pillH - 4f, tagTextPaint)
        }

        return copy
    }
}
