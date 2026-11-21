package dev.watchmemory.surfaces

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.*
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import dev.watchmemory.MainActivity
import dev.watchmemory.R

class RecordingTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
            .build()
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity).build()
        val modifiers = Modifiers.Builder()
            .setClickable(Clickable.Builder().setId("open_recorder").setOnClick(launch).build())
            .setSemantics(Semantics.Builder().setContentDescription("打开 KoeMemo 录音").build())
            .setBackground(Background.Builder()
                .setColor(argb(0xff20372f.toInt()))
                .setCorner(Corner.Builder().setRadius(dp(40f)).build())
                .build())
            .build()
        val icon = Image.Builder().setResourceId("mic").setWidth(dp(36f)).setHeight(dp(36f)).build()
        val button = Box.Builder().setWidth(dp(80f)).setHeight(dp(80f))
            .setModifiers(modifiers).addContent(icon).build()
        val layout = Box.Builder().setWidth(expand()).setHeight(expand()).addContent(button).build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCE_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val mic = ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_mic).build()
        val image = ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(mic).build()
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCE_VERSION)
            .addIdToImageMapping("mic", image).build()
        return Futures.immediateFuture(resources)
    }

    private companion object {
        const val RESOURCE_VERSION = "1"
    }
}
