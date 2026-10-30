package com.bandsync.app.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.bandsync.core.sheets.SheetFile
import com.bandsync.core.sheets.SheetStore
import java.io.IOException

class PdfImporter(private val context: Context, private val store: SheetStore) {
    /**
     * Copies a picked PDF into the store, which also means sending it later needs no access to the original.
     * Blocks, so call it off the main thread. Throws if the file isn't a PDF that Android can open.
     */
    fun import(uri: Uri): SheetFile {
        val id = context.contentResolver.openInputStream(uri)?.use(store::import)
            ?: throw IOException("Couldn't open the file")
        val file = store.fileFor(id)
        val pageCount = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        return SheetFile(id, displayName(uri), file.length(), pageCount)
    }

    private fun displayName(uri: Uri): String {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "Sheet.pdf"
    }
}
