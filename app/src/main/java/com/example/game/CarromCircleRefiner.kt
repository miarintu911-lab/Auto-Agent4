package com.example.game

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure Kotlin Sub-Pixel Circle Edge & Radial Hough Accumulator Refiner.
 *
 * Eliminates the 2-5px centroid drift caused by HSV blob thresholding, glowing striker halos,
 * drop shadows, or 4px grid sampling, and separates touching discs inside break clusters.
 * Runs in ~8-20ms on-device with zero native OpenCV .so bloat.
 */
object CarromCircleRefiner {

    /**
     * Refines a coarse detected piece center (in screen px) using Sobel gradient radial Hough votes
     * around the expected disc radius and parabolic sub-pixel peak interpolation.
     */
    fun refinePieceCenterSubPixel(
        bitmap: Bitmap,
        coarseCenterScreen: Point2D,
        expectedRadiusScreen: Float,
        scaleX: Float,
        scaleY: Float
    ): Point2D {
        if (expectedRadiusScreen < 6f || scaleX <= 0f || scaleY <= 0f) return coarseCenterScreen

        val cxBm = coarseCenterScreen.x / scaleX
        val cyBm = coarseCenterScreen.y / scaleY
        val rBm = (expectedRadiusScreen / scaleX).coerceIn(5f, 48f)

        val searchRadius = (rBm * 0.42f).roundToInt().coerceIn(3, 12)
        val margin = (rBm + searchRadius + 3).roundToInt()

        val minX = (cxBm - margin).roundToInt().coerceAtLeast(1)
        val maxX = (cxBm + margin).roundToInt().coerceAtMost(bitmap.width - 2)
        val minY = (cyBm - margin).roundToInt().coerceAtLeast(1)
        val maxY = (cyBm + margin).roundToInt().coerceAtMost(bitmap.height - 2)
        val patchW = maxX - minX + 1
        val patchH = maxY - minY + 1
        if (patchW < 10 || patchH < 10) return coarseCenterScreen

        // 1. Extract luma patch
        val luma = FloatArray(patchW * patchH)
        for (py in minY..maxY) {
            val rowOff = (py - minY) * patchW
            for (px in minX..maxX) {
                val p = bitmap.getPixel(px, py)
                luma[rowOff + (px - minX)] =
                    Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            }
        }

        // 2. Accumulate radial gradient Hough votes into (2*searchRadius+1)^2 grid
        val accSize = searchRadius * 2 + 1
        val acc = FloatArray(accSize * accSize)
        val rCandidates = floatArrayOf(rBm * 0.90f, rBm, rBm * 1.10f)

        for (y in 1 until patchH - 1) {
            val row = y * patchW
            for (x in 1 until patchW - 1) {
                // Sobel 3x3 gradient
                val gx = (
                    -luma[row - patchW + x - 1] + luma[row - patchW + x + 1] -
                        2f * luma[row + x - 1] + 2f * luma[row + x + 1] -
                        luma[row + patchW + x - 1] + luma[row + patchW + x + 1]
                    )
                val gy = (
                    -luma[row - patchW + x - 1] - 2f * luma[row - patchW + x] - luma[row - patchW + x + 1] +
                        luma[row + patchW + x - 1] + 2f * luma[row + patchW + x] + luma[row + patchW + x + 1]
                    )
                val mag = hypot(gx, gy)
                if (mag < 38f) continue

                val nx = gx / mag
                val ny = gy / mag
                val worldX = minX + x
                val worldY = minY + y

                // Vote along both inward and outward normal directions (handles bright-on-dark and dark-on-bright discs)
                for (rTest in rCandidates) {
                    for (sign in intArrayOf(-1, 1)) {
                        val vx = (worldX + sign * nx * rTest - cxBm).roundToInt() + searchRadius
                        val vy = (worldY + sign * ny * rTest - cyBm).roundToInt() + searchRadius
                        if (vx in 0 until accSize && vy in 0 until accSize) {
                            acc[vy * accSize + vx] += mag
                        }
                    }
                }
            }
        }

        // 3. Find peak in accumulator with mild center-prior so adjacent pieces don't hijack
        var bestIdx = -1
        var bestVal = 0f
        var bestAx = searchRadius
        var bestAy = searchRadius
        for (ay in 0 until accSize) {
            for (ax in 0 until accSize) {
                val distFromCoarse = hypot((ax - searchRadius).toFloat(), (ay - searchRadius).toFloat())
                val prior = 1f - (distFromCoarse / (searchRadius * 2.4f)).coerceIn(0f, 0.25f)
                val valWeighted = acc[ay * accSize + ax] * prior
                if (valWeighted > bestVal) {
                    bestVal = valWeighted
                    bestIdx = ay * accSize + ax
                    bestAx = ax
                    bestAy = ay
                }
            }
        }

        if (bestIdx < 0 || bestVal < 220f) {
            return coarseCenterScreen
        }

        // 4. Parabolic sub-pixel interpolation around peak
        var subDx = 0f
        var subDy = 0f
        if (bestAx in 1 until accSize - 1) {
            val left = acc[bestAy * accSize + bestAx - 1]
            val center = acc[bestAy * accSize + bestAx]
            val right = acc[bestAy * accSize + bestAx + 1]
            val denom = 2f * (left - 2f * center + right)
            if (abs(denom) > 1e-3f) {
                subDx = ((left - right) / denom).coerceIn(-0.5f, 0.5f)
            }
        }
        if (bestAy in 1 until accSize - 1) {
            val up = acc[(bestAy - 1) * accSize + bestAx]
            val center = acc[bestAy * accSize + bestAx]
            val down = acc[(bestAy + 1) * accSize + bestAx]
            val denom = 2f * (up - 2f * center + down)
            if (abs(denom) > 1e-3f) {
                subDy = ((up - down) / denom).coerceIn(-0.5f, 0.5f)
            }
        }

        val refinedBmX = cxBm + (bestAx - searchRadius) + subDx
        val refinedBmY = cyBm + (bestAy - searchRadius) + subDy
        return Point2D(refinedBmX * scaleX, refinedBmY * scaleY)
    }

    /**
     * Separates a touching multi-coin cluster (e.g., break rack or clustered coins along rails)
     * using a local Hough Circle response accumulator instead of crude grid bucketing.
     */
    fun separateClusterByHough(
        bitmap: Bitmap,
        clusterPointsScreen: List<Point2D>,
        colorHint: CoinColor,
        coinRadiusScreen: Float,
        scaleX: Float,
        scaleY: Float
    ): List<CarromPiece> {
        if (clusterPointsScreen.size < 4 || coinRadiusScreen < 6f) return emptyList()

        val minScreenX = clusterPointsScreen.minOf { it.x } - coinRadiusScreen * 1.4f
        val maxScreenX = clusterPointsScreen.maxOf { it.x } + coinRadiusScreen * 1.4f
        val minScreenY = clusterPointsScreen.minOf { it.y } - coinRadiusScreen * 1.4f
        val maxScreenY = clusterPointsScreen.maxOf { it.y } + coinRadiusScreen * 1.4f

        val minX = (minScreenX / scaleX).roundToInt().coerceIn(1, bitmap.width - 3)
        val maxX = (maxScreenX / scaleX).roundToInt().coerceIn(minX + 2, bitmap.width - 2)
        val minY = (minScreenY / scaleY).roundToInt().coerceIn(1, bitmap.height - 3)
        val maxY = (maxScreenY / scaleY).roundToInt().coerceIn(minY + 2, bitmap.height - 2)

        val patchW = maxX - minX + 1
        val patchH = maxY - minY + 1
        if (patchW < 8 || patchH < 8 || patchW * patchH > 220_000) return emptyList()

        val luma = FloatArray(patchW * patchH)
        for (py in minY..maxY) {
            val rowOff = (py - minY) * patchW
            for (px in minX..maxX) {
                val p = bitmap.getPixel(px, py)
                luma[rowOff + (px - minX)] =
                    Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
            }
        }

        val rBm = (coinRadiusScreen / scaleX).coerceIn(5f, 38f)
        val acc = FloatArray(patchW * patchH)

        for (y in 1 until patchH - 1) {
            val row = y * patchW
            for (x in 1 until patchW - 1) {
                val gx = (
                    -luma[row - patchW + x - 1] + luma[row - patchW + x + 1] -
                        2f * luma[row + x - 1] + 2f * luma[row + x + 1] -
                        luma[row + patchW + x - 1] + luma[row + patchW + x + 1]
                    )
                val gy = (
                    -luma[row - patchW + x - 1] - 2f * luma[row - patchW + x] - luma[row - patchW + x + 1] +
                        luma[row + patchW + x - 1] + 2f * luma[row + patchW + x] + luma[row + patchW + x + 1]
                    )
                val mag = hypot(gx, gy)
                if (mag < 36f) continue
                val nx = gx / mag
                val ny = gy / mag
                for (sign in intArrayOf(-1, 1)) {
                    val vx = (x + sign * nx * rBm).roundToInt()
                    val vy = (y + sign * ny * rBm).roundToInt()
                    if (vx in 0 until patchW && vy in 0 until patchH) {
                        acc[vy * patchW + vx] += mag
                    }
                }
            }
        }

        var maxAcc = 0f
        for (v in acc) {
            if (v > maxAcc) maxAcc = v
        }
        val minPeakThreshold = kotlin.math.max(900f, maxAcc * 0.38f)

        // Greedy Non-Maximum Suppression at min separation = 1.72 * rBm
        val candidates = ArrayList<Triple<Int, Int, Float>>()
        for (y in 2 until patchH - 2) {
            for (x in 2 until patchW - 2) {
                val v = acc[y * patchW + x]
                if (v < minPeakThreshold) continue
                // Local 3x3 peak check
                var isPeak = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (acc[(y + dy) * patchW + (x + dx)] > v) {
                            isPeak = false
                            break
                        }
                    }
                    if (!isPeak) break
                }
                if (isPeak) {
                    candidates.add(Triple(x, y, v))
                }
            }
        }

        candidates.sortByDescending { it.third }
        val minSepBm = rBm * 1.72f
        val minClusterProximityScreen = coinRadiusScreen * 0.72f
        val results = mutableListOf<CarromPiece>()

        for ((px, py, _) in candidates) {
            val worldBmX = minX + px
            val worldBmY = minY + py
            val screenPt = Point2D(worldBmX * scaleX, worldBmY * scaleY)

            // Verify peak center lies inside the segmented foreground coin cluster
            val nearCluster = clusterPointsScreen.any {
                hypot(it.x - screenPt.x, it.y - screenPt.y) <= minClusterProximityScreen
            }
            if (!nearCluster) continue

            // NMS against already-selected disc centers
            val tooClose = results.any {
                hypot((it.center.x / scaleX) - worldBmX, (it.center.y / scaleY) - worldBmY) < minSepBm
            }
            if (tooClose) continue

            // Sub-pixel refine this separated circle peak
            val refined = refinePieceCenterSubPixel(
                bitmap = bitmap,
                coarseCenterScreen = screenPt,
                expectedRadiusScreen = coinRadiusScreen,
                scaleX = scaleX,
                scaleY = scaleY
            )
            results.add(CarromPiece(colorHint, refined, coinRadiusScreen))
            if (results.size >= 14) break
        }

        return results
    }
}
