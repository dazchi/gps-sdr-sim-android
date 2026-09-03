package com.dazchi.livegpssdrsim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A simple analog thumbstick. Reports the thumb offset as a normalized vector:
 *   [xNorm] in [-1, 1], right-positive (maps to east)
 *   [yNorm] in [-1, 1], up-positive    (maps to north)
 * The magnitude of (xNorm, yNorm) is clamped to 1 (circular gate). On release
 * the thumb springs back to center and both components return to 0.
 *
 * The view only holds state; a caller-owned loop reads [xNorm]/[yNorm] each
 * tick. [onChange] fires on every touch update for callers that want to react
 * immediately (e.g. to start their motion loop on first touch).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile var xNorm: Float = 0f
        private set
    @Volatile var yNorm: Float = 0f
        private set

    var onChange: ((x: Float, y: Float) -> Unit)? = null

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f
    private var moveRadius = 0f
    private var thumbX = 0f
    private var thumbY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#66000000")
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#40000000")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3F51B5") // indigo
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        val pad = 12f
        baseRadius = min(w, h) / 2f - pad
        thumbRadius = baseRadius * 0.34f
        // Limit the thumb's centre so the whole thumb stays inside the base
        // circle (otherwise it clips at the edges).
        moveRadius = baseRadius - thumbRadius
        thumbX = centerX
        thumbY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = if (isEnabled) 1f else 0.35f
        basePaint.alpha = (0x33 * a).toInt()
        ringPaint.alpha = (0x66 * a).toInt()
        crossPaint.alpha = (0x40 * a).toInt()
        thumbPaint.alpha = (0xFF * a).toInt()

        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, crossPaint)
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, crossPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var dx = event.x - centerX
                var dy = event.y - centerY
                val dist = hypot(dx, dy)
                if (dist > moveRadius && dist > 0f) {
                    dx = dx / dist * moveRadius
                    dy = dy / dist * moveRadius
                }
                thumbX = centerX + dx
                thumbY = centerY + dy
                xNorm = dx / moveRadius
                yNorm = -dy / moveRadius // screen y is down; north is up
                invalidate()
                onChange?.invoke(xNorm, yNorm)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                reset()
                onChange?.invoke(xNorm, yNorm)
            }
        }
        return true
    }

    /** Spring the thumb back to center and zero the output vector. */
    fun reset() {
        xNorm = 0f
        yNorm = 0f
        thumbX = centerX
        thumbY = centerY
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) reset() else invalidate()
    }
}
