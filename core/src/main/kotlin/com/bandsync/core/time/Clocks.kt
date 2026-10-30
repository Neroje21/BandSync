package com.bandsync.core.time

/** Monotonic nanosecond clock. Never a wall clock: an NTP correction must not be able to move a beat. */
fun interface MonotonicClock {
    fun nowNs(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nowNs(): Long = System.nanoTime()
}

/** Converts between this device's clock and the master's, which is the band's shared reference. */
interface TimeBase {
    fun toMaster(localNs: Long): Long
    fun toLocal(masterNs: Long): Long

    object Master : TimeBase {
        override fun toMaster(localNs: Long): Long = localNs
        override fun toLocal(masterNs: Long): Long = masterNs
    }
}
