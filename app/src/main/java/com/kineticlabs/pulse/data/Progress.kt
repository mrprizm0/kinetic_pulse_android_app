package com.kineticlabs.pulse.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Riding streaks and week-by-week progress, worked out from saved tricks and rides. */
data class Progress(
    val currentStreak: Int,   // days in a row you've ridden, up to today (or yesterday)
    val longestStreak: Int,
    val daysRidden: Int,
    val weeks: List<Int>,     // tricks per week, oldest first, the last one is this week
    val thisWeek: Int,
    val lastWeek: Int,
)

fun computeProgress(
    tricks: List<WheelieRecord>,
    rideStarts: List<Long>,
    weeksBack: Int = 8,
    today: LocalDate = LocalDate.now(),
): Progress {
    val zone = ZoneId.systemDefault()
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // a day counts as "ridden" if it has a trick or a recorded ride
    val days = (tricks.map { day(it.time) } + rideStarts.map { day(it) }).toSortedSet()

    var longest = 0; var run = 0; var prev: LocalDate? = null
    for (d in days) {
        run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
        if (run > longest) longest = run
        prev = d
    }
    // current streak: still alive if you rode today or yesterday
    var current = 0
    var d = if (today in days) today else today.minusDays(1)
    while (d in days) { current++; d = d.minusDays(1) }

    val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (weeksBack - 1 downTo 0).map { back ->
        val start = thisMonday.minusWeeks(back.toLong())
        val end = start.plusDays(7)
        tricks.count { val t = day(it.time); !t.isBefore(start) && t.isBefore(end) }
    }
    return Progress(
        currentStreak = current,
        longestStreak = longest,
        daysRidden = days.size,
        weeks = weeks,
        thisWeek = weeks.last(),
        lastWeek = if (weeks.size >= 2) weeks[weeks.size - 2] else 0,
    )
}
