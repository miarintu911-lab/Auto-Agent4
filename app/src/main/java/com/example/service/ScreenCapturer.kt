package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val TAG = "ScreenCapturer"
private const val MAX_EDGE_PX = 1024

object ScreenCapturer {

    data class CaptureResult(
        val bitmap: Bitmap?,
        val note: String
    )

    suspend fun capture(
        service: AccessibilityService,
        enabled: Boolean,
        minFrameTimestampMs: Long = 0L
    ): CaptureResult {
        if (!enabled) {
            return CaptureResult(null, "Screenshot disabled in settings (UI Node Tree mode).")
        }

        // Fast zero-cooldown path: MediaProjection + ImageReader live stream (~20-60ms)
        if (LiveMediaProjectionCapturer.isLiveActive.value) {
            val liveBitmap = LiveMediaProjectionCapturer.grabFreshFrame(
                minTimestampMs = minFrameTimestampMs,
                waitTimeoutMs = 240L
            )
            if (liveBitmap != null) {
                return CaptureResult(liveBitmap, "Live MediaProjection Stream (0ms cooldown)")
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureResult(null, "Screenshot requires Android 11+ (using UI Node Tree only).")
        }

        val first = captureOnce(service)
        if (first.bitmap != null || !first.rateLimited) {
            return first.result
        }

        kotlinx.coroutines.delay(1_000L)
        return captureOnce(service).result
    }

    private data class AttemptResult(
        val result: CaptureResult,
        val bitmap: Bitmap?,
        val rateLimited: Boolean
    )

    private suspend fun captureOnce(service: AccessibilityService): AttemptResult {
        val result = withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine<AttemptResult> { cont ->
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(service),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                var hardwareBitmap: Bitmap? = null
                                try {
                                    hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    val scaled = softwareBitmap?.let { downscaleIfNeeded(it) }
                                    if (scaled != null && scaled !== softwareBitmap) {
                                        softwareBitmap.recycle()
                                    }
                                    if (cont.isActive) {
                                        val note = if (scaled != null) "Multimodal (Screenshot + UI Node Tree)"
                                        else "Screenshot wrap failed; using UI Node Tree."
                                        cont.resume(
                                            AttemptResult(
                                                result = CaptureResult(bitmap = scaled, note = note),
                                                bitmap = scaled,
                                                rateLimited = false
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed converting screenshot buffer", e)
                                    if (cont.isActive) {
                                        cont.resume(
                                            AttemptResult(
                                                result = CaptureResult(null, "Screenshot decode error; using UI Node Tree."),
                                                bitmap = null,
                                                rateLimited = false
                                            )
                                        )
                                    }
                                } finally {
                                    hardwareBitmap?.recycle()
                                    buffer.close()
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                val isRateLimited = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                val reason = when (errorCode) {
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                                        "Secure window blocked screenshot (using UI Node Tree)."
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                        "Screenshot rate-limited (using UI Node Tree)."
                                    else -> "Screenshot unavailable code=$errorCode (using UI Node Tree)."
                                }
                                Log.w(TAG, reason)
                                if (cont.isActive) {
                                    cont.resume(
                                        AttemptResult(
                                            result = CaptureResult(null, reason),
                                            bitmap = null,
                                            rateLimited = isRateLimited
                                        )
                                    )
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "takeScreenshot threw exception", e)
                    if (cont.isActive) {
                        cont.resume(
                            AttemptResult(
                                result = CaptureResult(null, "Screenshot error: ${e.localizedMessage}"),
                                bitmap = null,
                                rateLimited = false
                            )
                        )
                    }
                }
            }
        }

        return result ?: AttemptResult(
            result = CaptureResult(null, "Screenshot capture timed out; using UI Node Tree."),
            bitmap = null,
            rateLimited = false
        )
    }

    private fun downscaleIfNeeded(source: Bitmap): Bitmap {
        val maxDim = maxOf(source.width, source.height)
        if (maxDim <= MAX_EDGE_PX) return source
        val scale = MAX_EDGE_PX.toFloat() / maxDim.toFloat()
        val targetW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }
}
