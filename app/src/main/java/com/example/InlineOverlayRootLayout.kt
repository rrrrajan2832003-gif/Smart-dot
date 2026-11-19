package com.example

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * Root container for the transparent inline OCR overlay that guarantees Back key dismissal
 * regardless of which child view holds window focus.
 */
class InlineOverlayRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onBackKeyPressed: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackKeyPressed?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
