package com.bandsync.app.pdf

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.SheetPage
import com.bandsync.core.sheets.SheetStore
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Renders pages off the main thread, which matters beyond smooth scrolling: Nearby's callbacks run on the
 * main thread, so a render there would delay the timestamps clock sync depends on. PdfRenderer allows only
 * one open page at a time, hence the mutex.
 */
class SheetRenderer(private val store: SheetStore) {
    private val mutex = Mutex()
    private val documents = HashMap<FileId, Document>()
    private val closeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class Document(val descriptor: ParcelFileDescriptor, val renderer: PdfRenderer)

    /** Returns null for a page that can't be drawn, which leaves a blank page rather than a crash. */
    suspend fun render(page: SheetPage, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val document = documents[page.fileId] ?: open(page.fileId).also { documents[page.fileId] = it }
                if (page.pageIndex !in 0 until document.renderer.pageCount) return@withLock null
                document.renderer.openPage(page.pageIndex).use { pdfPage ->
                    val heightPx = (widthPx * pdfPage.height.toFloat() / pdfPage.width).roundToInt()
                    val bitmap = createBitmap(widthPx, heightPx)
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }

    /** Waits its turn on the mutex in the background, so closing can't pull a document out from under a render. */
    fun close() {
        closeScope.launch {
            mutex.withLock {
                documents.values.forEach {
                    it.renderer.close()
                    it.descriptor.close()
                }
                documents.clear()
            }
        }
    }

    private fun open(id: FileId): Document {
        val descriptor = ParcelFileDescriptor.open(store.fileFor(id), ParcelFileDescriptor.MODE_READ_ONLY)
        return Document(descriptor, PdfRenderer(descriptor))
    }
}
