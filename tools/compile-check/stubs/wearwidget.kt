@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.wear.widget
import android.content.Context
import android.widget.FrameLayout
class SwipeDismissFrameLayout(c: Context) : FrameLayout(c) {
    abstract class Callback { open fun onDismissed(layout: SwipeDismissFrameLayout) {} }
    fun addCallback(cb: Callback) {}
}
