package com.bandsync.app.session

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** The metronome's own thread, at the priority Android gives audio so the UI can't hold a beat back. */
fun newTickerDispatcher(): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                task.run()
            },
            "metronome",
        )
    }.asCoroutineDispatcher()
