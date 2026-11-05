package io.nekohasekai.sagernet.widget

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import kotlin.math.ceil

/** Only the small edge band is offscreen; neither the toolbar nor a screen bitmap is sampled. */
internal class TitleBarEdgeRenderer {
    private val fade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val blurMask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var gradientHeight = -1f
    private var blur: LocalBlur? = null
    private var blurRadius = 0f

    fun draw(canvas: Canvas, width: Int, top: Float, bottom: Float, clearFrom: Float, density: Float,
        hardwareBlur: Boolean, drawContent: (Canvas) -> Boolean): Boolean {
        if (width <= 0 || bottom <= top) return false
        // The first stationary row begins at clearFrom + its original padding, so
        // blur/fade must be completely gone by the toolbar's bottom, not 48dp later.
        val band = minOf(48f * density, bottom - top, (clearFrom - top).coerceAtLeast(0f))
        if (band <= 0f) {
            val save = canvas.save()
            canvas.clipRect(0f, top, width.toFloat(), bottom)
            val more = drawContent(canvas)
            canvas.restoreToCount(save)
            return more
        }
        gradients(band)
        val w = width.toFloat()
        var save = canvas.save()
        canvas.clipRect(0f, top + band, w, bottom)
        var more = drawContent(canvas)
        canvas.restoreToCount(save)

        save = canvas.save()
        canvas.clipRect(0f, top, w, top + band)
        canvas.translate(0f, top)
        val edge = canvas.saveLayer(0f, 0f, w, band, null)
        if (hardwareBlur && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
            val radius = 6f * density
            if (blurRadius != radius) { release(); blurRadius = radius }
            val renderer = blur ?: LocalBlur(radius).also { blur = it }
            val sampleHeight = ceil(minOf(bottom - top, band + renderer.gutter)).toInt()
            // Re-record every invalidated draw, not every drawingTime: a same-frame
            // title change/scroll must never replay stale content after quick navigation.
            renderer.record(width, sampleHeight) { recording ->
                recording.translate(0f, -top)
                more = drawContent(recording) || more
            }
            renderer.drawSharp(canvas)
            val overlay = canvas.saveLayer(0f, 0f, w, band, null)
            renderer.drawBlur(canvas)
            canvas.drawRect(0f, 0f, w, band, blurMask)
            canvas.restoreToCount(overlay)
        } else {
            canvas.translate(0f, -top)
            more = drawContent(canvas) || more
            canvas.translate(0f, top)
        }
        canvas.drawRect(0f, 0f, w, band, fade)
        canvas.restoreToCount(edge)
        canvas.restoreToCount(save)
        return more
    }

    private fun gradients(height: Float) {
        if (gradientHeight == height) return
        gradientHeight = height
        fade.shader = LinearGradient(0f, 0f, 0f, height,
            intArrayOf(Color.TRANSPARENT, 0xB3FFFFFF.toInt(), Color.WHITE),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        blurMask.shader = LinearGradient(0f, 0f, 0f, height,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, .2f, 1f), Shader.TileMode.CLAMP)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= 31) blur?.release()
        blur = null
    }

    @TargetApi(31)
    private class LocalBlur(radius: Float) {
        val gutter = radius * 2f
        private val sample = RenderNode("title-content-edge-sample")
        private val blurred = RenderNode("title-content-edge-blur").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        private var lastWidth = 0
        private var lastHeight = 0
        fun record(width: Int, height: Int, draw: (Canvas) -> Unit) {
            sample.setPosition(0, 0, width, height)
            val recording = sample.beginRecording(width, height)
            try {
                recording.clipRect(0, 0, width, height)
                draw(recording)
            } finally { sample.endRecording() }
            if (width != lastWidth || height != lastHeight) {
                blurred.setPosition(0, 0, width, height)
                val recordingBlur = blurred.beginRecording(width, height)
                try { recordingBlur.drawRenderNode(sample) } finally { blurred.endRecording() }
                lastWidth = width
                lastHeight = height
            }
        }
        fun drawSharp(canvas: Canvas) = canvas.drawRenderNode(sample)
        fun drawBlur(canvas: Canvas) = canvas.drawRenderNode(blurred)
        fun release() {
            sample.discardDisplayList()
            blurred.discardDisplayList()
            blurred.setRenderEffect(null)
        }
    }
}
