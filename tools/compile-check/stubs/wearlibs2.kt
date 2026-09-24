@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.wear.watchface.complications.datasource
import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
class ComplicationRequest { val complicationType: ComplicationType = TODO(); val complicationInstanceId: Int = 0 }
abstract class ComplicationDataSourceService : android.app.Service() {
    override fun onBind(i: android.content.Intent?): android.os.IBinder? = null
    interface ComplicationRequestListener { fun onComplicationData(d: ComplicationData?) }
    abstract fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener)
    open fun getPreviewData(type: ComplicationType): ComplicationData? = null
    open fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {}
    open fun onComplicationDeactivated(complicationInstanceId: Int) {}
}
interface ComplicationDataSourceUpdateRequester {
    fun requestUpdateAll()
    companion object { fun create(context: Context, complicationDataSourceComponent: ComponentName): ComplicationDataSourceUpdateRequester = TODO() }
}
