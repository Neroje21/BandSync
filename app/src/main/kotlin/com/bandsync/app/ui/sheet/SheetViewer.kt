package com.bandsync.app.ui.sheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bandsync.app.pdf.SheetRenderer
import com.bandsync.core.sheets.SheetPage
import com.bandsync.core.sheets.SheetStore

private const val MAX_ZOOM = 4f
private const val A4_RATIO = 0.707f

/** Rendered wider than the screen so notation stays readable zoomed in. Higher would risk running out of memory. */
private const val RENDER_OVERSCAN = 1.5f

/** The role's pages in order. Pinch to zoom; drag scrolls at normal size and pans when zoomed. */
@Composable
fun SheetViewer(pages: List<SheetPage>, store: SheetStore, modifier: Modifier = Modifier) {
    val renderer = remember(store) { SheetRenderer(store) }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }

    BoxWithConstraints(modifier.clipToBounds().background(Color.DarkGray)) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
            val maxX = (scale - 1) * widthPx / 2
            val maxY = (scale - 1) * heightPx / 2
            pan = Offset(
                (pan.x + panChange.x).coerceIn(-maxX, maxX),
                (pan.y + panChange.y).coerceIn(-maxY, maxY),
            )
        }

        Box(Modifier.fillMaxSize().transformable(transform, canPan = { scale > 1f })) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(pages) { _, page ->
                    SheetPageImage(page, (widthPx * RENDER_OVERSCAN).toInt(), renderer)
                }
            }
        }
    }
}

@Composable
private fun SheetPageImage(page: SheetPage, widthPx: Int, renderer: SheetRenderer) {
    val bitmap by produceState<ImageBitmap?>(null, page, widthPx) {
        value = renderer.render(page, widthPx)
    }
    val rendered = bitmap
    if (rendered == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(A4_RATIO).background(Color.White))
    } else {
        Image(rendered, contentDescription = null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}
