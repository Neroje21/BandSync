package com.bandsync.app.pdf

import android.content.Context
import com.bandsync.core.protocol.SongChange
import com.bandsync.core.protocol.SongConfig
import com.bandsync.core.protocol.TimeSignature
import com.bandsync.core.sheets.Role

private const val FOLDER = "test-tabs"
private val FOUR_FOUR = TimeSignature(4, 4)

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

    /** The tempo marks and time signatures printed on the tabs, so bar numbers match them all the way through. */
    val song = SongConfig(
        title = "Iron Man",
        bpm = 65,
        timeSignature = FOUR_FOUR,
        changes = listOf(
            SongChange(bar = 8, bpm = 65, timeSignature = TimeSignature(2, 4)),
            SongChange(bar = 9, bpm = 70, timeSignature = FOUR_FOUR),
            SongChange(bar = 11, bpm = 72, timeSignature = FOUR_FOUR),
            SongChange(bar = 13, bpm = 76, timeSignature = FOUR_FOUR),
            SongChange(bar = 61, bpm = 168, timeSignature = FOUR_FOUR),
            SongChange(bar = 85, bpm = 78, timeSignature = FOUR_FOUR),
            SongChange(bar = 97, bpm = 79, timeSignature = FOUR_FOUR),
            SongChange(bar = 103, bpm = 158, timeSignature = FOUR_FOUR),
        ),
    )

    fun isAvailable(context: Context): Boolean {
        val bundled = context.assets.list(FOLDER).orEmpty().toSet()
        return all.all { it.assetName in bundled }
    }
}
