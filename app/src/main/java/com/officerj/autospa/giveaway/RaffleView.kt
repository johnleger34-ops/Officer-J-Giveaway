package com.officerj.autospa.giveaway

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.random.Random

class RaffleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val logo: Bitmap? by lazy { (ContextCompat.getDrawable(context, R.drawable.officer_j_logo) as? BitmapDrawable)?.bitmap }
    var tickets: List<Pair<Int,String>> = emptyList()
    private var progress = 0f
    private var active: Pair<Int,String>? = null
    var drawing = false; private set

    fun drawWinner(onWinner: (Pair<Int,String>) -> Unit) {
        if (drawing || tickets.isEmpty()) return
        drawing = true
        active = tickets[Random.nextInt(tickets.size)]
        ValueAnimator.ofFloat(0f,1f).apply {
            duration = 5000; interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) = Unit
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    drawing = false
                    active?.let(onWinner)
                    invalidate()
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    drawing = false
                }
                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat(); val h=height.toFloat()
        paint.color=0xFF08101A.toInt(); canvas.drawRoundRect(w*.16f,h*.35f,w*.84f,h*.84f,24f,24f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=0xFF566678.toInt(); canvas.drawRoundRect(w*.16f,h*.35f,w*.84f,h*.84f,24f,24f,paint); paint.style=Paint.Style.FILL
        paint.color=0xFF020408.toInt(); canvas.drawRoundRect(w*.32f,h*.31f,w*.68f,h*.39f,12f,12f,paint)
        logo?.let { b -> canvas.drawBitmap(b,null,Rect((w*.34f).toInt(),(h*.53f).toInt(),(w*.66f).toInt(),(h*.73f).toInt()),paint) }
        val item=active
        if (item!=null) {
            val rise = if(progress<.82f) progress/.82f else 1f
            val y = h*.46f - rise*h*.34f
            paint.color=Color.WHITE
            canvas.drawRoundRect(w*.23f,y,w*.77f,y+h*.25f,12f,12f,paint)
            logo?.let { b -> canvas.drawBitmap(b,null,Rect((w*.43f).toInt(),(y+h*.02f).toInt(),(w*.57f).toInt(),(y+h*.10f).toInt()),paint) }
            textPaint.color=Color.BLACK; textPaint.typeface=Typeface.DEFAULT_BOLD; textPaint.textSize=w*.055f
            canvas.drawText(item.second.take(24),w*.5f,y+h*.16f,textPaint)
            textPaint.textSize=w*.035f; canvas.drawText("Raffle #${item.first}",w*.5f,y+h*.21f,textPaint)
        } else {
            textPaint.color=Ui.MUTED; textPaint.textSize=w*.045f
            canvas.drawText("READY TO DRAW",w*.5f,h*.18f,textPaint)
        }
    }
}
