package com.officerj.autospa.giveaway

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class WheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class WheelSegment(
        val displayName: String,
        val tickets: List<Pair<Int, String>>
    ) {
        val weight: Int get() = tickets.size
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }

    private val logo: Bitmap? by lazy {
        (ContextCompat.getDrawable(context, R.drawable.officer_j_logo) as? BitmapDrawable)?.bitmap
    }

    var tickets: List<Pair<Int, String>> = emptyList()
        set(value) {
            field = value
            segments = buildSegments(value)
            invalidate()
        }

    private var segments: List<WheelSegment> = emptyList()
    private var rotationDegrees = 0f
    var spinning = false
        private set

    /**
     * Selects one numbered ticket at random. Duplicate names are drawn as one larger
     * weighted slice, but every numbered ticket keeps an equal chance of winning.
     */
    fun spin(onWinner: (Pair<Int, String>) -> Unit) {
        if (spinning || tickets.isEmpty() || segments.isEmpty()) return

        spinning = true
        val winningTicket = tickets[Random.nextInt(tickets.size)]
        val winningSegmentIndex = segments.indexOfFirst { segment ->
            segment.tickets.any { it.first == winningTicket.first }
        }.coerceAtLeast(0)

        val totalWeight = tickets.size.toFloat()
        var startAngle = 0f
        for (index in 0 until winningSegmentIndex) {
            startAngle += 360f * segments[index].weight / totalWeight
        }
        val winningSweep = 360f * segments[winningSegmentIndex].weight / totalWeight
        val segmentCenter = startAngle + winningSweep / 2f

        // Android arcs begin at 3 o'clock. The fixed pointer is at 12 o'clock (270°).
        val current = normalizeDegrees(rotationDegrees)
        val currentSegmentCenter = normalizeDegrees(segmentCenter + current)
        val additionalToPointer = normalizeDegrees(270f - currentSegmentCenter)
        val target = rotationDegrees + (360f * 8f) + additionalToPointer

        ValueAnimator.ofFloat(rotationDegrees, target).apply {
            duration = 5000L
            interpolator = DecelerateInterpolator(2.1f)
            addUpdateListener {
                rotationDegrees = it.animatedValue as Float
                invalidate()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) {
                    spinning = false
                }
                override fun onAnimationEnd(animation: Animator) {
                    rotationDegrees = normalizeDegrees(rotationDegrees)
                    spinning = false
                    invalidate()
                    onWinner(winningTicket)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val pad = size * 0.07f
        val radius = size / 2f - pad
        val cx = width / 2f
        val cy = height / 2f

        paint.style = Paint.Style.FILL
        paint.color = 0xFF07111F.toInt()
        canvas.drawCircle(cx, cy, radius + 10f, paint)

        if (segments.isEmpty()) {
            textPaint.textSize = size * 0.055f
            textPaint.color = Ui.MUTED
            canvas.drawText("ADD ENTRIES TO SPIN", cx, cy, textPaint)
            drawPointer(canvas, cx, cy - radius - 2f, size)
            return
        }

        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)

        val wheelRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val totalWeight = tickets.size.toFloat()
        var startAngle = 0f

        segments.forEachIndexed { index, segment ->
            val sweep = 360f * segment.weight / totalWeight

            paint.style = Paint.Style.FILL
            paint.color = if (index % 2 == 0) 0xFF0E1A29.toInt() else 0xFF075BC4.toInt()
            canvas.drawArc(wheelRect, startAngle, sweep, true, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (segments.size > 300) 0.5f else 1.5f
            paint.color = 0xFF6EAFFF.toInt()
            canvas.drawArc(wheelRect, startAngle, sweep, true, paint)
            paint.style = Paint.Style.FILL

            drawSegmentName(
                canvas = canvas,
                name = segment.displayName,
                centerAngle = startAngle + sweep / 2f,
                sweep = sweep,
                cx = cx,
                cy = cy,
                radius = radius,
                wheelSize = size
            )

            startAngle += sweep
        }

        canvas.restore()

        paint.color = 0xFF05070A.toInt()
        canvas.drawCircle(cx, cy, radius * 0.25f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Ui.BLUE
        canvas.drawCircle(cx, cy, radius * 0.25f, paint)
        paint.style = Paint.Style.FILL

        logo?.let { bitmap ->
            val half = (radius * 0.19f).toInt()
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(
                    (cx - half).toInt(),
                    (cy - half).toInt(),
                    (cx + half).toInt(),
                    (cy + half).toInt()
                ),
                paint
            )
        }

        drawPointerName(canvas, cx, cy, radius, size)
        drawPointer(canvas, cx, cy - radius - 2f, size)
    }

    private fun drawSegmentName(
        canvas: Canvas,
        name: String,
        centerAngle: Float,
        sweep: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        wheelSize: Float
    ) {
        // Keep every unique entrant visible. Duplicate tickets are grouped into one
        // proportional slice, so a person with ten entries gets a slice ten times larger.
        val density = resources.displayMetrics.scaledDensity
        val minText = 6.5f * density
        val maxText = 16f * density
        val angularRoom = (Math.toRadians(sweep.toDouble()) * radius * 0.72f).toFloat()
        textPaint.textSize = (angularRoom * 0.34f).coerceIn(minText, maxText)
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.setShadowLayer(2.5f, 0f, 1f, Color.BLACK)

        val radialWidth = radius * 0.56f
        val label = fitText(name, radialWidth, textPaint)
        val radians = Math.toRadians(centerAngle.toDouble())
        val textRadius = radius * 0.66f
        val x = cx + cos(radians).toFloat() * textRadius
        val y = cy + sin(radians).toFloat() * textRadius

        canvas.save()
        canvas.rotate(centerAngle, x, y)
        val normalized = normalizeDegrees(centerAngle)
        if (normalized in 90f..270f) {
            canvas.rotate(180f, x, y)
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, x, y + textPaint.textSize * 0.34f, textPaint)
        canvas.restore()
        textPaint.clearShadowLayer()
    }

    private fun fitText(value: String, maxWidth: Float, p: Paint): String {
        val clean = value.trim().ifBlank { "Unnamed" }
        if (p.measureText(clean) <= maxWidth) return clean
        if (maxWidth <= p.measureText("…")) return "…"

        var end = clean.length
        while (end > 1 && p.measureText(clean.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return clean.substring(0, end.coerceAtLeast(1)) + "…"
    }

    private fun buildSegments(source: List<Pair<Int, String>>): List<WheelSegment> {
        if (source.isEmpty()) return emptyList()

        data class MutableSegment(
            val displayName: String,
            val tickets: MutableList<Pair<Int, String>>
        )

        val grouped = linkedMapOf<String, MutableSegment>()
        source.forEach { ticket ->
            val display = ticket.second.trim().ifBlank { "Unnamed" }
            val key = display.lowercase(Locale.ROOT)
            val segment = grouped.getOrPut(key) {
                MutableSegment(display, mutableListOf())
            }
            segment.tickets += ticket
        }

        return grouped.values.map { WheelSegment(it.displayName, it.tickets.toList()) }
    }

    private fun drawPointerName(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float) {
        if (segments.isEmpty()) return
        val pointerAngleOnWheel = normalizeDegrees(270f - rotationDegrees)
        val totalWeight = tickets.size.toFloat()
        var start = 0f
        val active = segments.firstOrNull { segment ->
            val sweep = 360f * segment.weight / totalWeight
            val inside = pointerAngleOnWheel >= start && pointerAngleOnWheel < start + sweep
            start += sweep
            inside
        } ?: segments.last()

        val boxWidth = radius * 1.20f
        val boxHeight = size * 0.075f
        val rect = RectF(cx - boxWidth / 2f, cy - radius * 0.88f, cx + boxWidth / 2f, cy - radius * 0.88f + boxHeight)
        paint.style = Paint.Style.FILL
        paint.color = 0xE8050A12.toInt()
        canvas.drawRoundRect(rect, boxHeight / 2f, boxHeight / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Ui.BLUE
        canvas.drawRoundRect(rect, boxHeight / 2f, boxHeight / 2f, paint)
        paint.style = Paint.Style.FILL

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = (size * 0.032f).coerceAtLeast(13f * resources.displayMetrics.scaledDensity)
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        val label = fitText(active.displayName, boxWidth * 0.88f, textPaint)
        canvas.drawText(label, cx, rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun drawPointer(canvas: Canvas, cx: Float, y: Float, size: Float) {
        val pointer = Path().apply {
            moveTo(cx, y + size * 0.07f)
            lineTo(cx - size * 0.04f, y)
            lineTo(cx + size * 0.04f, y)
            close()
        }
        paint.color = Ui.BLUE
        paint.style = Paint.Style.FILL
        canvas.drawPath(pointer, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.WHITE
        canvas.drawPath(pointer, paint)
        paint.style = Paint.Style.FILL
    }

    private fun normalizeDegrees(value: Float): Float {
        val normalized = value % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }
}
