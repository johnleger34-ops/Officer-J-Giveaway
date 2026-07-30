package com.officerj.autospa.giveaway

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

object Ui {
    const val BG = 0xFF05070A.toInt()
    const val PANEL = 0xFF0A0F16.toInt()
    const val PANEL2 = 0xFF111A27.toInt()
    const val BLUE = 0xFF087BFF.toInt()
    const val BLUE_DARK = 0xFF0044B7.toInt()
    const val SILVER = 0xFFE7ECF2.toInt()
    const val MUTED = 0xFF8995A7.toInt()
    const val RED = 0xFFFF4B4B.toInt()

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun bg(color: Int, radius: Int = 12, stroke: Int = 0, strokeColor: Int = BLUE): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    fun text(c: Context, value: String, size: Float = 16f, color: Int = SILVER, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
        }

    fun button(c: Context, label: String, outline: Boolean = false): Button = Button(c).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        isAllCaps = true
        background = if (outline) bg(PANEL, c.dp(10), c.dp(1), BLUE) else GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(BLUE, BLUE_DARK)
        ).apply { cornerRadius = c.dp(10).toFloat() }
        minHeight = c.dp(48)
        setPadding(c.dp(12), 0, c.dp(12), 0)
    }

    fun input(c: Context, hintText: String): EditText = EditText(c).apply {
        hint = hintText
        setHintTextColor(MUTED)
        setTextColor(SILVER)
        textSize = 15f
        setSingleLine(true)
        background = bg(PANEL, c.dp(8), c.dp(1), 0xFF24436E.toInt())
        setPadding(c.dp(12), 0, c.dp(12), 0)
        minHeight = c.dp(48)
    }

    fun card(c: Context, padding: Int = 14): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(PANEL, c.dp(14), c.dp(1), 0xFF1E334F.toInt())
        setPadding(c.dp(padding), c.dp(padding), c.dp(padding), c.dp(padding))
    }

    fun add(parent: ViewGroup, child: View, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) {
        val lp = LinearLayout.LayoutParams(w, h)
        lp.topMargin = parent.context.dp(top)
        parent.addView(child, lp)
    }
}
