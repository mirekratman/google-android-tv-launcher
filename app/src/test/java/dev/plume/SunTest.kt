package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected times come from the astral library (python), for the local date around each solar noon. */
class SunTest {
    private fun times(lat: Double, lon: Double, now: Long) = Sun.day(lat, lon, now) as Sun.Day.Times

    private fun near(expected: Long, actual: Long) = assertEquals(expected.toDouble(), actual.toDouble(), 3 * 60_000.0)

    @Test fun torontoSummer() = times(43.65, -79.38, 1782062354000L).let { near(1782034581401L, it.rise); near(1782090145185L, it.set) }

    @Test fun torontoWinter() = times(43.65, -79.38, 1797873320000L).let { near(1797857287828L, it.rise); near(1797889396712L, it.set) }

    @Test fun londonEquinox() = times(51.5074, -0.1278, 1774008485000L).let { near(1773986619295L, it.rise); near(1774030394100L, it.set) }

    @Test fun sydneyWinter() = times(-33.87, 151.21, 1782007012000L).let { near(1781989211768L, it.rise); near(1782024815258L, it.set) }

    @Test fun delhi() = times(28.61, 77.21, 1792910114000L).let { near(1792889896202L, it.rise); near(1792930307782L, it.set) }

    @Test fun darkAroundSunriseAndAtNight() {
        val rise = 1782034581401L // Toronto, 21 June
        assertTrue(Sun.dark(43.65, -79.38, rise - 10 * 60_000))
        assertFalse(Sun.dark(43.65, -79.38, rise + 10 * 60_000))
        assertTrue(Sun.dark(43.65, -79.38, 1782062354000L + 12 * 3_600_000)) // solar midnight
    }

    @Test fun polar() {
        assertTrue(Sun.day(69.65, 18.96, 1797850800000L) is Sun.Day.AlwaysDown) // Tromsø, December
        assertTrue(Sun.day(69.65, 18.96, 1782039600000L) is Sun.Day.AlwaysUp) // Tromsø, June
    }

    @Test fun noPlaceUsesTheClock() {
        assertTrue(autoDark(null, 0L, 6))
        assertFalse(autoDark(null, 0L, 7))
        assertFalse(autoDark(null, 0L, 18))
        assertTrue(autoDark(null, 0L, 19))
    }
}
