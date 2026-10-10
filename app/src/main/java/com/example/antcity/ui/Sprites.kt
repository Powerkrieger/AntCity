package com.example.antcity.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.hex.HexBuilding
import com.example.antcity.core.hex.Stat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Sprites from `assets/sprites/`, looked up by key (e.g. `building.farm`). The keys and their files
 * are listed in `sprites.json`; replacing art means replacing the PNG. Missing sprites return null so
 * callers can fall back to an emoji.
 */
class Sprites(private val assets: AssetManager) {
    @Serializable
    private class Manifest(val size: Int, val sprites: Map<String, String>)

    private val files: Map<String, String> = runCatching {
        assets.open("$DIR/sprites.json").use { Json.decodeFromString<Manifest>(it.readBytes().decodeToString()).sprites }
    }.getOrElse {
        Log.w(TAG, "No sprite manifest", it)
        emptyMap()
    }

    private val cache = HashMap<String, ImageBitmap?>()

    operator fun get(key: String): ImageBitmap? = cache.getOrPut(key) {
        val file = files[key] ?: return@getOrPut null.also { Log.w(TAG, "Unknown sprite $key") }
        runCatching { assets.open("$DIR/$file").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }
            .onFailure { Log.w(TAG, "Can't load sprite $key ($file)", it) }
            .getOrNull()
    }

    private companion object {
        const val DIR = "sprites"
        const val TAG = "Sprites"
    }
}

val LocalSprites = staticCompositionLocalOf<Sprites> { error("No Sprites provided") }

// Keys follow `<category>.<enum name in lower case>`, matching sprites.json.
val HexBuilding.spriteKey get() = "building.${name.lowercase()}"
val Stat.spriteKey get() = "icon.${name.lowercase()}"
val DuelBuilding.spriteKey get() = "building.${name.lowercase()}"

object TileSprites {
    val GRASS = listOf("tile.grass_1", "tile.grass_2", "tile.grass_3")
    const val FOUNDATION = "tile.foundation"
    const val FOG = "tile.fog"
    const val HIT = "tile.hit"
    const val MISS = "tile.miss"
    const val UNKNOWN = "tile.unknown"
    const val CRACK = "tile.crack"
}

/** Draws [image] as a square of [size] pixels centred on [center]. */
fun DrawScope.drawSprite(image: ImageBitmap, center: Offset, size: Float, alpha: Float = 1f) {
    val px = size.roundToInt()
    drawImage(
        image,
        dstOffset = IntOffset((center.x - size / 2).roundToInt(), (center.y - size / 2).roundToInt()),
        dstSize = IntSize(px, px),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

/** A sprite in the UI, or [fallback] (usually an emoji) if it has no art. */
@Composable
fun SpriteIcon(key: String, fallback: String, size: Dp, modifier: Modifier = Modifier) {
    val image = LocalSprites.current[key]
    if (image != null) {
        Image(image, contentDescription = null, modifier.size(size), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
    } else {
        Text(fallback, modifier, fontSize = with(LocalDensity.current) { (size * 0.8f).toSp() })
    }
}
