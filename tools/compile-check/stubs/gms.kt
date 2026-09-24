@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.android.gms.tasks
import java.util.concurrent.TimeUnit
abstract class Task<T>
object Tasks {
    @JvmStatic fun <T> await(t: Task<T>): T = TODO()
    @JvmStatic fun <T> await(t: Task<T>, timeout: Long, unit: TimeUnit): T = TODO()
}
