@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.android.gms.wearable
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import java.io.InputStream

class Asset { companion object { @JvmStatic fun createFromBytes(b: ByteArray): Asset = TODO() } }
class DataMap {
    fun putString(k: String, v: String) {}
    fun putLong(k: String, v: Long) {}
    fun putInt(k: String, v: Int) {}
    fun putBoolean(k: String, v: Boolean) {}
    fun putAsset(k: String, v: Asset) {}
    fun putStringArrayList(k: String, v: ArrayList<String>) {}
    fun putDataMapArrayList(k: String, v: ArrayList<DataMap>) {}
    fun getDataMapArrayList(k: String): ArrayList<DataMap>? = null
    fun getString(k: String): String? = null
    fun getLong(k: String): Long = 0
    fun getLong(k: String, d: Long): Long = d
    fun getInt(k: String): Int = 0
    fun getInt(k: String, d: Int): Int = d
    fun getBoolean(k: String, d: Boolean): Boolean = d
    fun getAsset(k: String): Asset? = null
    fun getStringArrayList(k: String): ArrayList<String>? = null
    fun containsKey(k: String): Boolean = false
}
interface DataItem { val uri: Uri }
class DataMapItem { val dataMap: DataMap = DataMap(); companion object { @JvmStatic fun fromDataItem(i: DataItem): DataMapItem = TODO() } }
class DataItemBuffer : Iterable<DataItem> { override fun iterator(): Iterator<DataItem> = TODO(); fun release() {} }
interface DataEvent { val type: Int; val dataItem: DataItem; companion object { const val TYPE_CHANGED = 1 } }
class DataEventBuffer : Iterable<DataEvent> { override fun iterator(): Iterator<DataEvent> = TODO(); fun release() {} }
class PutDataRequest { fun setUrgent(): PutDataRequest = this; companion object { const val WEAR_URI_SCHEME = "wear" } }
class PutDataMapRequest { val dataMap: DataMap = DataMap(); fun asPutDataRequest(): PutDataRequest = TODO(); companion object { @JvmStatic fun create(p: String): PutDataMapRequest = TODO() } }
class GetFdForAssetResponse { val inputStream: InputStream = TODO() }
abstract class DataClient {
    abstract fun putDataItem(r: PutDataRequest): Task<DataItem>
    abstract fun getDataItems(): Task<DataItemBuffer>
    abstract fun getDataItems(uri: Uri): Task<DataItemBuffer>
    abstract fun getFdForAsset(a: Asset): Task<GetFdForAssetResponse>
}
interface Node { val id: String }
abstract class NodeClient { abstract val connectedNodes: Task<List<Node>> }
abstract class MessageClient { abstract fun sendMessage(node: String, path: String, data: ByteArray): Task<Int> }
object Wearable {
    @JvmStatic fun getDataClient(c: Context): DataClient = TODO()
    @JvmStatic fun getNodeClient(c: Context): NodeClient = TODO()
    @JvmStatic fun getMessageClient(c: Context): MessageClient = TODO()
}
interface MessageEvent { val path: String; val data: ByteArray }
abstract class WearableListenerService : android.app.Service() {
    override fun onBind(i: android.content.Intent?): android.os.IBinder? = null
    open fun onDataChanged(dataEvents: DataEventBuffer) {}
    open fun onMessageReceived(event: MessageEvent) {}
}
