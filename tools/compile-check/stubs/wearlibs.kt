@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.wear.watchface.complications.data
import android.app.PendingIntent
import android.graphics.drawable.Icon
enum class ComplicationType { LONG_TEXT, SMALL_IMAGE, SHORT_TEXT }
enum class SmallImageType { PHOTO, ICON }
abstract class ComplicationData
abstract class ComplicationText
class PlainComplicationText : ComplicationText() { class Builder(t: CharSequence) { fun build(): PlainComplicationText = TODO() } }
class NoDataComplicationData : ComplicationData()
class SmallImage { class Builder(i: Icon, t: SmallImageType) { fun build(): SmallImage = TODO() } }
class LongTextComplicationData : ComplicationData() {
    class Builder(text: ComplicationText, contentDescription: ComplicationText) { fun setTapAction(p: PendingIntent?): Builder = this; fun build(): LongTextComplicationData = TODO() }
}
class SmallImageComplicationData : ComplicationData() {
    class Builder(smallImage: SmallImage, contentDescription: ComplicationText) { fun setTapAction(p: PendingIntent?): Builder = this; fun build(): SmallImageComplicationData = TODO() }
}
