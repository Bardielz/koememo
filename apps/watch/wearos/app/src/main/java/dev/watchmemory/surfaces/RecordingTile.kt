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
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName).setClassName(MainActivity::class.java.name).build()).build()
        val button = Box.Builder().setWidth(dp(80f)).setHeight(dp(80f)).setModifiers(Modifiers.Builder()
            .setClickable(Clickable.Builder().setId("open_recorder").setOnClick(launch).build())
            .setSemantics(Semantics.Builder().setContentDescription("打开 KoeMemo 录音").build())
            .setBackground(Background.Builder().setColor(argb(0xff20372f.toInt()))
                .setCorner(Corner.Builder().setRadius(dp(40f)).build()).build()).build())
            .addContent(Image.Builder().setResourceId("mic").setWidth(dp(36f)).setHeight(dp(36f)).build()).build()
        val layout = Box.Builder().setWidth(expand()).setHeight(expand()).addContent(button).build()
        return Futures.immediateFuture(TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout)).build())
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest) =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").addIdToImageMapping("mic",
            ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder()
                .setResourceId(R.drawable.ic_mic).build()).build()).build())
}
