package com.bandsync.app.pdf

import android.content.Context
import com.bandsync.core.sheets.Role

private const val FOLDER = "library"

data class LibrarySheet(val assetPath: String, val displayName: String, val part: Role)

data class LibrarySong(val title: String, val sheets: List<LibrarySheet>)

/**
 * Songs ready to play. Each is a folder named after the song, holding one PDF per part named after the part,
 * such as `Iron Man/drums.pdf`, so adding a song takes no code. Tempo presets come from the PDFs when a song loads.
 *
 * The folder is in src/debug/assets, so a release build has no songs and never offers the library. It's also
 * gitignored, so a fresh clone won't either.
 */
object SongLibrary {
    fun songs(context: Context): List<LibrarySong> =
        context.assets.list(FOLDER).orEmpty().sorted().mapNotNull { title ->
            val sheets = context.assets.list("$FOLDER/$title").orEmpty().mapNotNull { file ->
                Role.entries.firstOrNull { file.equals("${it.name}.pdf", ignoreCase = true) }?.let { part ->
                    LibrarySheet("$FOLDER/$title/$file", "$title - ${part.label}.pdf", part)
                }
            }
            if (sheets.isEmpty()) null else LibrarySong(title, sheets.sortedBy { it.part })
        }
}
