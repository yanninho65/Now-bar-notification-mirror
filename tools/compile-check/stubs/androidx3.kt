@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.content
import android.content.Context
import android.content.Intent
object ContextCompat {
    @JvmStatic fun startForegroundService(c: Context, i: Intent) {}
    @JvmStatic fun checkSelfPermission(c: Context, p: String): Int = 0
}
