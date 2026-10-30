package com.bandsync.app.pdf

import android.content.Context
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.sheets.Role

private const val FOLDER = "test-tabs"

data class TestTab(val assetName: String, val displayName: String, val role: Role) {
    val assetPath: String get() = "$FOLDER/$assetName"
}

/**
 * A sample song for trying the app without copying PDFs to the master phone. The PDFs sit in src/debug/assets,
 * so a release build has none and never offers them. They are also gitignored, so a fresh clone won't either.
 */
object TestTabs {
    val all = listOf(
        TestTab("iron-man-guitar.pdf", "Iron Man - Guitar.pdf", Role.GUITAR),
        TestTab("iron-man-bass.pdf", "Iron Man - Bass.pdf", Role.BASS),
        TestTab("iron-man-drums.pdf", "Iron Man - Drums.pdf", Role.DRUMS),
    )

    /** The tempo and time signatures come from the tabs themselves. */
    val song = SongConfig(title = "Iron Man")

    fun isAvailable(context: Context): Boolean {
        val bundled = context.assets.list(FOLDER).orEmpty().toSet()
        return all.all { it.assetName in bundled }
    }
}
