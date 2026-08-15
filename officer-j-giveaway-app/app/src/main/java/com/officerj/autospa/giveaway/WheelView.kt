package com.officerj.autospa.giveaway

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class WheelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val logo: Bitmap? by lazy {
        (ContextCompat.getDrawable(context, R.drawable.officer_j_logo) as? BitmapDrawable)?.bitmap
    }
    var tickets: List<Pair<Int, String>> = emptyList()
        set(value) { field = value; invalidate() }
    private var rotationDegrees = 0f
    var spinning = false; private set

    fun spin(forcedTicket: Pair<Int, String>? = null, onWinner: (Pair<Int, String>) -> Unit) {
        if (spinning || tickets.isEmpty()) return
        spinning = true
        val winnerIndex = forcedTicket?.let { target -> tickets.indexOfFirst { it.first == target.first } }?.takeIf { it >= 0 } ?: Random.nextInt(tickets.size)
        val sweep = 360f / tickets.size
        val targetCenter = winnerIndex * sweep + sweep / 2f
        val target = 360f * 8 + (270f - targetCenter)
        ValueAnimator.ofFloat(rotationDegrees, target).apply {
            duration = 5000
            interpolator = DecelerateInterpolator(2.1f)
            addUpdateListener { rotationDegrees = it.animatedValue as Float; invalidate() }
            doOnEndCompat {
                rotationDegrees %= 360f
                spinning = false
                onWinner(tickets[winnerIndex])
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val pad = size * .07f
        val r = size / 2f - pad
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = 0xFF07111F.toInt()
        canvas.drawCircle(cx, cy, r + 10f, paint)
        if (tickets.isEmpty()) {
            textPaint.textSize = size * .055f
            textPaint.color = Ui.MUTED
            canvas.drawText("ADD ENTRIES TO SPIN", cx, cy, textPaint)
            drawPointer(canvas, cx, cy - r - 2f, size)
            return
        }
        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        val rect = RectF(cx-r, cy-r, cx+r, cy+r)
        val shown = if (tickets.size <= 120) tickets else tickets.take(120)
        val sweep = 360f / shown.size
        shown.forEachIndexed { i, ticket ->
            paint.color = if (i % 2 == 0) 0xFF0E1A29.toInt() else 0xFF075BC4.toInt()
            canvas.drawArc(rect, i*sweep, sweep, true, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = 0xFF6EAFFF.toInt()
            canvas.drawArc(rect, i*sweep, sweep, true, paint); paint.style = Paint.Style.FILL
            if (shown.size <= 32) {
                val angle = Math.toRadians((i*sweep+sweep/2f).toDouble())
                val tx = cx + cos(angle).toFloat() * r * .68f
                val ty = cy + sin(angle).toFloat() * r * .68f
                canvas.save(); canvas.rotate(i*sweep+sweep/2f+90f, tx, ty)
                textPaint.textSize = (size * .035f).coerceAtMost(16f * resources.displayMetrics.scaledDensity)
                textPaint.color = Color.WHITE
                val label = ticket.second.take(16)
                canvas.drawText(label, tx, ty, textPaint)
                canvas.restore()
            }
        }
        canvas.restore()
        paint.color = 0xFF05070A.toInt(); canvas.drawCircle(cx, cy, r*.25f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Ui.BLUE
        canvas.drawCircle(cx, cy, r*.25f, paint); paint.style = Paint.Style.FILL
        logo?.let { b ->
            val half = (r*.19f).toInt()
            canvas.drawBitmap(b, null, Rect((cx-half).toInt(),(cy-half).toInt(),(cx+half).toInt(),(cy+half).toInt()), paint)
        }
        drawPointer(canvas, cx, cy-r-2f, size)
    }

    private fun drawPointer(canvas: Canvas, cx: Float, y: Float, size: Float) {
        val p = Path().apply { moveTo(cx, y+size*.07f); lineTo(cx-size*.04f,y); lineTo(cx+size*.04f,y); close() }
        paint.color = Ui.BLUE; paint.style = Paint.Style.FILL; canvas.drawPath(p, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.WHITE; canvas.drawPath(p, paint); paint.style = Paint.Style.FILL
    }
}

private fun ValueAnimator.doOnEndCompat(block: () -> Unit) {
    addListener(object : android.animation.Animator.AnimatorListener {
        override fun onAnimationStart(animation: android.animation.Animator) {}
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
        override fun onAnimationCancel(animation: android.animation.Animator) {}
        override fun onAnimationRepeat(animation: android.animation.Animator) {}
    })
}
