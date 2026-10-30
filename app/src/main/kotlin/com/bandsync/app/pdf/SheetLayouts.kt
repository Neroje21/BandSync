package com.bandsync.app.pdf

import com.bandsync.core.sheets.BarLayout
import com.bandsync.core.sheets.BarLayoutParser
import com.bandsync.core.sheets.FileId
import com.bandsync.core.sheets.SheetStore
import java.util.concurrent.ConcurrentHashMap

/** Reads each PDF's bar positions once. It takes a moment and can't change, because files are named by content. */
class SheetLayouts(private val store: SheetStore) {
    private val known = ConcurrentHashMap<FileId, BarLayout>()

    /** Blocks, so call it off the main thread. A file with no readable bars gets an empty layout and no arrow. */
    fun layoutsFor(files: List<FileId>): Map<FileId, BarLayout> =
        files.filter(store::contains).associateWith { id ->
            known.getOrPut(id) {
                BarLayoutParser.parse(store.fileFor(id).readBytes()) ?: BarLayout(emptyList())
            }
        }
}
