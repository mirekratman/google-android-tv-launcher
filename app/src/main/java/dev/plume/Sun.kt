package dev.plume

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sunrise and sunset worked out on the TV (the NOAA sunrise equation, within a few minutes), so the Auto theme
 * follows the sky with no request to anyone. Times are epoch milliseconds.
 */
internal object Sun {
    /** Light all day (midnight sun), dark all day (polar night), or a sunrise and a sunset. */
    sealed class Day {
        object AlwaysUp : Day()
        object AlwaysDown : Day()
        class Times(val rise: Long, val set: Long) : Day()
    }

    /** The sun's day closest to [now] at [lat], [lon] (degrees, east positive). */
    fun day(lat: Double, lon: Double, now: Long): Day {
        // The solar noon nearest to now: its own sunrise and sunset decide whether it's light now.
        val n = floor(julian(now) - J2000 - 0.0009 + lon / 360).toLong()
        val noons = (n - 1..n + 1).map { noon(it.toDouble(), lon) }
        val i = noons.indices.minByOrNull { abs(noons[it].first - julian(now)) }!!
        val (transit, decl) = noons[i]
        val cosH = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
        if (cosH < -1) return Day.AlwaysUp
        if (cosH > 1) return Day.AlwaysDown
        val h = acos(cosH) * 180 / PI / 360
        return Day.Times(millis(transit - h), millis(transit + h))
    }

    /** Dark between sunset and sunrise. */
    fun dark(lat: Double, lon: Double, now: Long) = when (val d = day(lat, lon, now)) {
        Day.AlwaysUp -> false
        Day.AlwaysDown -> true
        is Day.Times -> now < d.rise || now > d.set
    }

    /** Solar noon (Julian date) and the sun's declination (radians) on day [n] after J2000. */
    private fun noon(n: Double, lon: Double): Pair<Double, Double> {
        val j = n + 0.0009 - lon / 360
        val m = (357.5291 + 0.98560028 * j).mod(360.0)
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val l = (m + c + 180 + 102.9372).mod(360.0)
        val transit = J2000 + j + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * l))
        return transit to asin(sin(rad(l)) * sin(rad(23.4397)))
    }

    private const val J2000 = 2451545.0
    private fun julian(ms: Long) = ms / 86_400_000.0 + 2440587.5
    private fun millis(jd: Double) = ((jd - 2440587.5) * 86_400_000).toLong()
    private fun rad(d: Double) = d * PI / 180
}
