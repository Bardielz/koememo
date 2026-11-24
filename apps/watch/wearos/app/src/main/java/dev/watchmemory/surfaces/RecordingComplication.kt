package dev.watchmemory.surfaces

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.watchmemory.R

class RecordingComplication : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(data(request.complicationType, preview = false))
    }

    override fun getPreviewData(type: ComplicationType) = data(type, preview = true)

    private fun data(type: ComplicationType, preview: Boolean): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build()
        val description = PlainComplicationText.Builder("打开 KoeMemo 录音").build()
        val tap = if (preview) null else openRecorder(this)
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, description).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder("录音").build(), description)
                    .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}
