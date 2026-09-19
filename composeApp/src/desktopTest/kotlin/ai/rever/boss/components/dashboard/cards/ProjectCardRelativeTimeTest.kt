package ai.rever.boss.components.dashboard.cards

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/**
 * Day-boundary matrix for [formatRelativeTime].
 *
 * The day-named buckets are calendar semantics: "Yesterday" is the previous
 * calendar date in the active zone - never a 24-48h elapsed window - and the
 * elapsed buckets ("Just now", "Nm ago", "Nh ago") only apply within the
 * timestamp's own calendar date.
 *
 * Every case pins a fixed zone and fixed instants, so nothing here races the
 * wall clock or the machine timezone. Asia/Kolkata (fixed +05:30, no DST)
 * pins the calendar boundaries; America/New_York covers the 2024 fall-back
 * day where 25 real hours fit inside one calendar date.
 */
class ProjectCardRelativeTimeTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(
        zone: ZoneId,
        date: String,
        time: String,
    ): Long {
        val local = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** Render [formatRelativeTime] for an mtime/now pair of local date-times in [zone]. */
    private fun between(
        zone: ZoneId,
        mtimeDate: String,
        mtimeTime: String,
        nowDate: String,
        nowTime: String,
    ): String {
        val mtime = at(zone, mtimeDate, mtimeTime)
        val now = at(zone, nowDate, nowTime)
        return formatRelativeTime(mtime, now, zone)
    }

    private fun absoluteDate(mtime: Long): String = SimpleDateFormat("MMM d").format(Date(mtime))

    @Test
    fun `zero timestamp still renders Never`() {
        assertEquals("Never", formatRelativeTime(0L, now = 0L, zone = kolkata))
    }

    @Test
    fun `future timestamps keep rendering Just now`() {
        // Future mtimes are handled in a separate issue; pin that this
        // change leaves their existing rendering untouched.
        assertEquals("Just now", between(kolkata, "2026-09-19", "12:05", "2026-09-19", "12:00"))
        assertEquals("Just now", between(kolkata, "2026-09-20", "08:00", "2026-09-19", "12:00"))
    }

    @Test
    fun `elapsed buckets within today keep their strings and thresholds`() {
        assertEquals("Just now", between(kolkata, "2026-09-19", "21:59:30", "2026-09-19", "22:00"))
        assertEquals("15m ago", between(kolkata, "2026-09-19", "21:45", "2026-09-19", "22:00"))
        assertEquals("8h ago", between(kolkata, "2026-09-19", "14:00", "2026-09-19", "22:00"))
        assertEquals("21h ago", between(kolkata, "2026-09-19", "00:30", "2026-09-19", "22:00"))
    }

    @Test
    fun `two minutes across midnight read Yesterday, not Just now`() {
        // 23:59 last night vs 00:01 tonight: elapsed says 2m ago, the
        // calendar says Yesterday.
        assertEquals("Yesterday", between(kolkata, "2026-09-18", "23:59", "2026-09-19", "00:01"))
        // The boundary into Just now is calendar-owned too: 50 elapsed
        // seconds still read Yesterday once midnight has been crossed.
        assertEquals("Yesterday", between(kolkata, "2026-09-18", "23:59:10", "2026-09-19", "00:00"))
    }

    @Test
    fun `an hour gap that crosses midnight reads Yesterday, not Nh ago`() {
        assertEquals("Yesterday", between(kolkata, "2026-09-18", "23:00", "2026-09-19", "01:00"))
    }

    @Test
    fun `a mid-day yesterday timestamp still reads Yesterday`() {
        assertEquals("Yesterday", between(kolkata, "2026-09-18", "12:00", "2026-09-19", "15:00"))
    }

    @Test
    fun `yesterday early morning with 47h59m elapsed still reads Yesterday`() {
        // Both 27h and 47h59m elapsed can be calendar-yesterday; the fix
        // must not over-correct those into the absolute date.
        assertEquals("Yesterday", between(kolkata, "2026-09-18", "00:00", "2026-09-19", "23:59"))
    }

    @Test
    fun `late-evening timestamp two calendar days ago is not Yesterday`() {
        // 47h elapsed lands inside the old 24-48h Yesterday window, but the
        // calendar date is two days back.
        val mtime = at(kolkata, "2026-09-17", "23:00")
        val now = at(kolkata, "2026-09-19", "22:00")
        val result = formatRelativeTime(mtime, now, kolkata)
        assertNotEquals("Yesterday", result)
        assertEquals(absoluteDate(mtime), result)
    }

    @Test
    fun `24h gap spanning two midnights is not Yesterday`() {
        val mtime = at(kolkata, "2026-09-17", "23:59")
        val now = at(kolkata, "2026-09-19", "00:01")
        val result = formatRelativeTime(mtime, now, kolkata)
        assertNotEquals("Yesterday", result)
        assertEquals(absoluteDate(mtime), result)
    }

    @Test
    fun `fall-back day keeps an elapsed-beyond-24h mtime in Today`() {
        // America/New_York fell back on 2024-11-03, making that calendar
        // date 25 real hours long: 00:01 EDT to 23:59 EST is 24h58m elapsed
        // but still the same calendar date - Today, not Yesterday.
        assertEquals("24h ago", between(newYork, "2024-11-03", "00:01", "2024-11-03", "23:59"))
    }
}
