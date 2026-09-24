@file:Suppress("unused", "UNUSED_PARAMETER")
package kotlinx.coroutines
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
interface Job : CoroutineContext.Element { fun cancel() {} }
interface CoroutineScope { val coroutineContext: CoroutineContext }
fun CoroutineScope(c: CoroutineContext): CoroutineScope = TODO()
fun SupervisorJob(): Job = TODO()
object Dispatchers { val IO: CoroutineContext = EmptyCoroutineContext; val Main: CoroutineContext = EmptyCoroutineContext }
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = TODO()
fun CoroutineScope.cancel() {}
val CoroutineScope.isActive: Boolean get() = true
suspend fun delay(ms: Long) {}
suspend fun <T> withContext(c: CoroutineContext, block: suspend CoroutineScope.() -> T): T = TODO()
