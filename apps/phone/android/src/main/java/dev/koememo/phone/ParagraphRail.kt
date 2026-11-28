package dev.koememo.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/** A touch target wider than the visible marks. Every filtered paragraph remains reachable. */
internal class ParagraphRail(context: Context, private val jump: (Int) -> Unit) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private var count = 0
    private var selected = 0
    private val inset get() = minOf(dp(12), height / 3)
    init { isFocusable = true; isClickable = true; contentDescription = "段落快速导航" }

    fun update(size: Int, current: Int) {
        val index = current.coerceIn(0, (size - 1).coerceAtLeast(0))
        if (count == size && selected == index) return
        count = size; selected = index
        visibility = if (size > 1) VISIBLE else GONE
        stateDescription = if (size == 0) "无段落" else "第 ${index + 1} 段，共 ${size} 段"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count < 2) return
        val range = (height - 2 * inset).coerceAtLeast(1)
        val marks = count.coerceAtMost((range / dp(8)).coerceAtLeast(1) + 1)
        val active = (selected.toFloat() * (marks - 1) / (count - 1)).roundToInt()
        ink.color = foreground
        for (i in 0 until marks) {
            ink.alpha = if (i == active) 255 else 68
            ink.strokeWidth = dp(if (i == active) 3 else 2).toFloat()
            val y = inset + i * range.toFloat() / (marks - 1)
            canvas.drawLine(width * 0.36f, y, width * 0.36f + dp(if (i == active) 15 else 8), y, ink)
        }
    }
    private fun go(index: Int) {
        if (count < 2) return
        val target = index.coerceIn(0, count - 1)
        update(count, target)
        jump(target)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (count < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
                go(((event.y - inset) / (height - 2 * inset).coerceAtLeast(1) * (count - 1)).roundToInt())
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> { go(selected - 1); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { go(selected + 1); true }
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (count < 2) return
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, count.toFloat(), selected + 1f)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (count < 2) return super.performAccessibilityAction(action, arguments)
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> go(selected + 1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> go(selected - 1)
            android.R.id.accessibilityActionSetProgress -> go((arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false).roundToInt() - 1)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
}
