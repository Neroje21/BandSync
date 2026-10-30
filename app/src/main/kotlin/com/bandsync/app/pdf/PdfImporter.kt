package com.bandsync.app.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.bandsync.core.sheets.SheetFile
import com.bandsync.core.sheets.SheetStore
import java.io.IOException
import java.io.InputStream

class PdfImporter(private val context: Context, private val store: SheetStore) {
    /**
     * Copies a picked PDF into the store, which also means sending it later needs no access to the original.
     * Blocks, so call it off the main thread. Throws if the file isn't a PDF that Android can open.
     */
    fun import(uri: Uri): SheetFile {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        return import(input, displayName(uri))
    }

    fun importAsset(path: String, name: String): SheetFile = import(context.assets.open(path), name)

    private fun import(input: InputStream, name: String): SheetFile {
        val id = input.use(store::import)
        val file = store.fileFor(id)
        val pageCount = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
        return SheetFile(id, name, file.length(), pageCount)
    }

    private fun displayName(uri: Uri): String {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "Sheet.pdf"
    }
}
