package com.bandsync.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.bandsync.app.pdf.SheetLayouts
import com.bandsync.core.sheets.Role
import com.bandsync.core.sheets.SheetStore
import com.bandsync.core.time.MonotonicClock
import com.bandsync.core.time.SystemMonotonicClock
import java.io.File

class BandSyncApp : Application() {
    val clock: MonotonicClock = SystemMonotonicClock

    /** In the cache directory so the system can reclaim old sheets. They can always be sent again. */
    val store: SheetStore by lazy { SheetStore(File(cacheDir, "sheets")) }

    val sheetLayouts: SheetLayouts by lazy { SheetLayouts(store) }

    val profile: Profile by lazy { Profile(this) }
}

/** The name and role this phone last used, so a band doesn't re-enter them at every rehearsal. */
class Profile(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString("name", null) ?: Build.MODEL
        set(value) = prefs.edit { putString("name", value) }

    var role: Role
        get() = prefs.getString("role", null)
            ?.let { saved -> Role.entries.firstOrNull { it.name == saved } }
            ?: Role.GUITAR
        set(value) = prefs.edit { putString("role", value.name) }
}
