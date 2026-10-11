package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated Arc Reactor Hologram View
 * Renders glowing cyan energy rings, rotating tick indicators, and pulsating center core.
 */
class HologramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var currentState = State.IDLE
    private var rotationAngle = 0f
    private var pulseFactor = 0f

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00f0ff")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#00f0ff")
    }

    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00f0ff")
    }

    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#007799")
    }

    private val boundsRect = RectF()

    init {
        // Continuous rotation animator
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Pulsing breathing animator
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulseFactor = it.animatedValue as Float
            }
            start()
        }
    }

    fun setState(state: State) {
        currentState = state
        when (state) {
            State.IDLE -> {
                outerRingPaint.color = Color.parseColor("#00f0ff")
                coreGlowPaint.color = Color.parseColor("#00f0ff")
            }
            State.LISTENING -> {
                outerRingPaint.color = Color.parseColor("#00ff88") // Green energetic
                coreGlowPaint.color = Color.parseColor("#00ff88")
            }
            State.THINKING -> {
                outerRingPaint.color = Color.parseColor("#ffb300") // Gold quantum
                coreGlowPaint.color = Color.parseColor("#ffb300")
            }
            State.SPEAKING -> {
                outerRingPaint.color = Color.parseColor("#00f0ff")
                coreGlowPaint.color = Color.parseColor("#80e5ff")
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - 12f
        if (radius <= 0) return

        boundsRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Outer Ring
        canvas.drawCircle(cx, cy, radius, outerRingPaint)

        // 2. Rotating Arc Segments & Ticks
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        val tickCount = 12
        for (i in 0 until tickCount) {
            val angle = Math.toRadians((i * (360.0 / tickCount)))
            val r1 = radius - 14f
            val r2 = radius - 2f
            val x1 = cx + (r1 * cos(angle)).toFloat()
            val y1 = cy + (r1 * sin(angle)).toFloat()
            val x2 = cx + (r2 * cos(angle)).toFloat()
            val y2 = cy + (r2 * sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
        canvas.restore()

        // 3. Counter-rotating inner ring
        canvas.save()
        canvas.rotate(-rotationAngle * 1.5f, cx, cy)
        val innerRadius = radius * 0.65f
        canvas.drawCircle(cx, cy, innerRadius, innerRingPaint)
        canvas.restore()

        // 4. Central Glowing Core with pulse
        val corePulseRadius = (radius * 0.35f) + (pulseFactor * (radius * 0.08f))
        coreGlowPaint.alpha = (180 + (pulseFactor * 75)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, corePulseRadius, coreGlowPaint)

        // Core bright center
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 230
        }
        canvas.drawCircle(cx, cy, radius * 0.16f, centerPaint)
    }
}
