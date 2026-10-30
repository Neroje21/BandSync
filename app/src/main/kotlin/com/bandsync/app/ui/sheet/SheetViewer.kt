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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bandsync.app.pdf.SheetLayouts
import com.bandsync.app.pdf.SheetRenderer
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.sheets.SheetCursor
import com.bandsync.core.sheets.SheetPage
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.sheets.SheetTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 4f
private const val A4_RATIO = 0.707f

/** Rendered wider than the screen so notation stays readable zoomed in. Higher would risk running out of memory. */
private const val RENDER_OVERSCAN = 1.5f

/** Keeps the current line this far down the view, as a fraction of its height, so the line above stays in sight. */
private const val CURRENT_LINE_POSITION = 1f / 6

private val ArrowColor = Color(0xFFE5392F)
private val ArrowHalfWidth = 7.dp
private val ArrowHeight = 10.dp
private val ArrowLineWidth = 2.5.dp

/**
 * The part's pages in order, with an arrow showing what to play and the page scrolling to keep up. Pinch to
 * zoom; drag scrolls at normal size and pans when zoomed. Following pauses while zoomed in, so that reading
 * a detail isn't interrupted.
 */
@Composable
fun SheetViewer(
    pages: List<SheetPage>,
    store: SheetStore,
    layouts: SheetLayouts,
    transport: TransportState,
    masterNow: () -> Long,
    modifier: Modifier = Modifier,
) {
    val renderer = remember(store) { SheetRenderer(store) }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    val listState = rememberLazyListState()
    val cursor = rememberSheetCursor(pages, layouts, transport, masterNow)

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

        FollowCursor(listState, cursor, widthPx, isZoomed = { scale > 1f })

        Box(Modifier.fillMaxSize().transformable(transform, canPan = { scale > 1f })) {
            LazyColumn(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(pages) { index, page ->
                    SheetPageImage(page, (widthPx * RENDER_OVERSCAN).toInt(), renderer, Modifier.sheetArrow(cursor, index))
                }
            }
        }
    }
}

/** Worked out once per display frame from the shared timeline, the same way the beat flash is. */
@Composable
private fun rememberSheetCursor(
    pages: List<SheetPage>,
    layouts: SheetLayouts,
    transport: TransportState,
    masterNow: () -> Long,
): State<SheetCursor?> {
    val cursor = remember { mutableStateOf<SheetCursor?>(null) }
    val currentMasterNow by rememberUpdatedState(masterNow)
    val tracker by produceState<SheetTracker?>(null, pages) {
        value = withContext(Dispatchers.IO) {
            SheetTracker(pages, layouts.layoutsFor(pages.map { it.fileId }.distinct()))
        }
    }
    LaunchedEffect(transport, tracker) {
        val active = tracker
        if (active == null || transport == TransportState.Stopped) {
            cursor.value = null
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { cursor.value = active.locate(transport, currentMasterNow()) }
        }
    }
    return cursor
}

/** Scrolls only when the arrow reaches a new line, so reading ahead stays possible in between. */
@Composable
private fun FollowCursor(
    listState: LazyListState,
    cursor: State<SheetCursor?>,
    widthPx: Int,
    isZoomed: () -> Boolean,
) {
    val currentIsZoomed by rememberUpdatedState(isZoomed)
    LaunchedEffect(listState, widthPx) {
        snapshotFlow { cursor.value?.let { it.listIndex to it.top } }
            .distinctUntilChanged()
            .collectLatest { line ->
                if (line == null || currentIsZoomed()) return@collectLatest
                val (listIndex, top) = line
                val layout = listState.layoutInfo
                val pageHeight = layout.visibleItemsInfo.firstOrNull { it.index == listIndex }?.size
                    ?: (widthPx / A4_RATIO).toInt()
                val offset = top * pageHeight - layout.viewportSize.height * CURRENT_LINE_POSITION
                listState.animateScrollToItem(listIndex, offset.toInt().coerceAtLeast(0))
            }
    }
}

/** Reads the cursor while drawing, so moving the arrow redraws the page and never recomposes it. */
private fun Modifier.sheetArrow(cursor: State<SheetCursor?>, listIndex: Int): Modifier = drawWithContent {
    drawContent()
    val place = cursor.value?.takeIf { it.listIndex == listIndex } ?: return@drawWithContent
    val x = place.x * size.width
    val top = place.top * size.height
    val tip = top + ArrowHeight.toPx()
    drawLine(
        color = ArrowColor.copy(alpha = 0.7f),
        start = Offset(x, tip),
        end = Offset(x, place.bottom * size.height),
        strokeWidth = ArrowLineWidth.toPx(),
    )
    val head = Path().apply {
        moveTo(x - ArrowHalfWidth.toPx(), top)
        lineTo(x + ArrowHalfWidth.toPx(), top)
        lineTo(x, tip)
        close()
    }
    drawPath(head, ArrowColor)
}

@Composable
private fun SheetPageImage(page: SheetPage, widthPx: Int, renderer: SheetRenderer, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, page, widthPx) {
        value = renderer.render(page, widthPx)
    }
    val rendered = bitmap
    if (rendered == null) {
        Box(modifier.fillMaxWidth().aspectRatio(A4_RATIO).background(Color.White))
    } else {
        Image(rendered, contentDescription = null, modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}
