package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val TAG = "LiveProjectionCapturer"
private const val PROJECTION_MAX_EDGE = 1024

/**
 * Continuous zero-cooldown screen frame buffer backed by MediaProjection + VirtualDisplay + ImageReader.
 * Eliminates AccessibilityService.takeScreenshot()'s forced ~1000ms rate limit for rapid Carrom play
 * and closed-loop striker-slider verification.
 */
object LiveMediaProjectionCapturer {

    private val lock = Any()
    private val _isLiveActive = MutableStateFlow(false)
    val isLiveActive: StateFlow<Boolean> = _isLiveActive.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var latestBitmap: Bitmap? = null
    private var latestTimestampMs: Long = 0L
    private var reusableBuffer: Bitmap? = null
    private var nextFrameWaiter: CompletableDeferred<Unit>? = null
    private var onStoppedListener: (() -> Unit)? = null

    fun startFromService(
        context: Context,
        resultCode: Int,
        data: Intent,
        onStopped: () -> Unit
    ): Boolean {
        stop()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false

        return try {
            val projection = manager.getMediaProjection(resultCode, data) ?: return false
            val (screenW, screenH, densityDpi) = readDisplayMetrics(context)
            val maxDim = maxOf(screenW, screenH)
            val scale = if (maxDim > PROJECTION_MAX_EDGE) PROJECTION_MAX_EDGE.toFloat() / maxDim.toFloat() else 1f
            val capW = (screenW * scale).roundToInt().coerceAtLeast(120)
            val capH = (screenH * scale).roundToInt().coerceAtLeast(120)

            val thread = HandlerThread("TapPilotMediaProjection").apply { start() }
            val handler = Handler(thread.looper)

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user")
                    stop()
                    onStopped()
                }
            }
            // Register callback BEFORE createVirtualDisplay (required on Android 14+)
            projection.registerCallback(callback, handler)

            val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    if (planes.isEmpty()) return@setOnImageAvailableListener
                    val plane = planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * capW
                    val paddedW = capW + (rowPadding / pixelStride.coerceAtLeast(1)).coerceAtLeast(0)

                    val rawBmp = synchronized(lock) {
                        val existing = reusableBuffer
                        if (existing != null && existing.width == paddedW && existing.height == capH && !existing.isRecycled) {
                            existing
                        } else {
                            existing?.recycle()
                            Bitmap.createBitmap(paddedW, capH, Bitmap.Config.ARGB_8888).also {
                                reusableBuffer = it
                            }
                        }
                    }
                    rawBmp.copyPixelsFromBuffer(buffer)
                    val cropped = if (paddedW != capW) {
                        Bitmap.createBitmap(rawBmp, 0, 0, capW, capH)
                    } else {
                        rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                    }

                    val now = SystemClock.uptimeMillis()
                    val waiterToComplete: CompletableDeferred<Unit>?
                    synchronized(lock) {
                        latestBitmap?.recycle()
                        latestBitmap = cropped
                        latestTimestampMs = now
                        waiterToComplete = nextFrameWaiter
                        nextFrameWaiter = null
                    }
                    waiterToComplete?.complete(Unit)
                } catch (e: Exception) {
                    Log.w(TAG, "Frame decode warning", e)
                } finally {
                    image.close()
                }
            }, handler)

            val vDisplay = projection.createVirtualDisplay(
                "TapPilotLiveCapture",
                capW,
                capH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                callback2VirtualDisplay(),
                handler
            )

            synchronized(lock) {
                mediaProjection = projection
                virtualDisplay = vDisplay
                imageReader = reader
                handlerThread = thread
                bgHandler = handler
                projectionCallback = callback
                onStoppedListener = onStopped
            }
            _isLiveActive.value = true
            Log.i(TAG, "Live MediaProjection stream started (${capW}x${capH})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting MediaProjection", e)
            stop()
            false
        }
    }

    suspend fun grabFreshFrame(
        minTimestampMs: Long = 0L,
        waitTimeoutMs: Long = 220L
    ): Bitmap? {
        if (!_isLiveActive.value) return null

        val cachedNow = synchronized(lock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled && latestTimestampMs >= minTimestampMs) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                null
            }
        }
        if (cachedNow != null) return cachedNow

        // Wait briefly for the next ImageReader frame rendered after overlay hide
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) {
            val existing = nextFrameWaiter
            if (existing != null && !existing.isCompleted) {
                // Chain onto existing waiter
            } else {
                nextFrameWaiter = waiter
            }
        }
        val activeWaiter = synchronized(lock) { nextFrameWaiter ?: waiter }
        withTimeoutOrNull(waitTimeoutMs) {
            activeWaiter.await()
        }

        return synchronized(lock) {
            val bmp = latestBitmap
            if (bmp != null && !bmp.isRecycled) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                null
            }
        }
    }

    fun stop() {
        _isLiveActive.value = false
        val waiter: CompletableDeferred<Unit>?
        synchronized(lock) {
            waiter = nextFrameWaiter
            nextFrameWaiter = null
            runCatching { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } }
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            runCatching { mediaProjection?.stop() }
            runCatching { handlerThread?.quitSafely() }
            latestBitmap?.recycle()
            latestBitmap = null
            reusableBuffer?.recycle()
            reusableBuffer = null
            latestTimestampMs = 0L
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            handlerThread = null
            bgHandler = null
            projectionCallback = null
            onStoppedListener = null
        }
        waiter?.cancel()
    }

    private fun callback2VirtualDisplay(): VirtualDisplay.Callback? = null

    private fun readDisplayMetrics(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val densityDpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(120), bounds.height().coerceAtLeast(120), densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels.coerceAtLeast(120), dm.heightPixels.coerceAtLeast(120), dm.densityDpi.coerceAtLeast(160))
        }
    }
}
