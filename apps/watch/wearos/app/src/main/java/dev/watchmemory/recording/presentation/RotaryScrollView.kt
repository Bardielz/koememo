package dev.watchmemory.recording.presentation

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Handle bezel/crown motion even when an interactive child holds focus. */
class RotaryScrollView(context: Context) : ScrollView(context) {
    private val factor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    private var remainder = 0f

    init {
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            return super.dispatchGenericMotionEvent(event)
        }
        val pixels = remainder - event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor
        val step = pixels.roundToInt()
        remainder = pixels - step
        scrollBy(0, step)
        return true
    }
}
