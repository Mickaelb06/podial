package app.podial

import kotlin.math.*

/** Position du Soleil et zone de nuit pour la carte du monde en temps réel. */
object Sun {
    /** (déclinaison, longitude du point subsolaire) en degrés, longitude positive vers l'est. */
    fun position(ms: Long): Pair<Double, Double> {
        val n = ms / 86400000.0 + 2440587.5 - 2451545.0          // jours depuis J2000
        val meanLon = (280.460 + 0.9856474 * n) % 360
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360)
        val lambda = Math.toRadians(meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val dec = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmst = (280.46061837 + 360.98564736629 * n) % 360
        var lon = (Math.toDegrees(ra) - gmst) % 360
        if (lon > 180) lon -= 360
        if (lon < -180) lon += 360
        return Math.toDegrees(dec) to lon
    }

    /** Hauteur du Soleil (degrés) en un point. */
    fun altitude(lat: Double, lon: Double, ms: Long): Double {
        val (dec, sub) = position(ms)
        val d = Math.toRadians(dec); val p = Math.toRadians(lat); val h = Math.toRadians(lon - sub)
        return Math.toDegrees(asin(sin(p) * sin(d) + cos(p) * cos(d) * cos(h)))
    }

    /** Une bande sombre : longitudes [lon0, lon1], latitudes [lo, hi]. */
    class Band(val lon0: Float, val lon1: Float, val lo: Float, val hi: Float)

    /** Latitudes (degrés) où le Soleil est sous la hauteur [alt] sur le méridien [lon] : liste d'intervalles [bas, haut]. */
    fun darkIntervals(lon: Double, dec: Double, sub: Double, alt: Double): List<Pair<Double, Double>> {
        val a = sin(Math.toRadians(dec)); val b = cos(Math.toRadians(dec)) * cos(Math.toRadians(lon - sub))
        val s0 = sin(Math.toRadians(alt))
        val r = hypot(a, b)
        fun s(phi: Double) = a * sin(phi) + b * cos(phi)
        if (r < 1e-12) return if (0.0 < s0) listOf(-90.0 to 90.0) else emptyList()
        if (s0 >= r) return listOf(-90.0 to 90.0)
        if (s0 <= -r) return emptyList()
        val theta = atan2(b, a); val base = asin(s0 / r)
        val half = PI / 2
        val cuts = ArrayList<Double>()
        for (k in -2..2) for (c in listOf(base - theta, PI - base - theta)) {
            val phi = c + 2 * PI * k
            if (phi > -half && phi < half) cuts += phi
        }
        cuts.sort()
        val edges = listOf(-half) + cuts + listOf(half)
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 0 until edges.size - 1) {
            val mid = (edges[i] + edges[i + 1]) / 2
            if (s(mid) < s0) out += Math.toDegrees(edges[i]) to Math.toDegrees(edges[i + 1])
        }
        return out
    }

    /** Zone où le Soleil est sous [alt] degrés, en bandes de [step] degrés de longitude. */
    fun bands(ms: Long, alt: Double, step: Double = 0.5): List<Band> {
        val (dec, sub) = position(ms)
        val out = ArrayList<Band>()
        var lon = -180.0
        while (lon < 180.0) {
            for ((lo, hi) in darkIntervals(lon + step / 2, dec, sub, alt)) out += Band(lon.toFloat(), (lon + step).toFloat(), lo.toFloat(), hi.toFloat())
            lon += step
        }
        return out
    }
}
