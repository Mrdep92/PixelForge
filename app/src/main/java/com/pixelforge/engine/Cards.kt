package com.pixelforge.engine

/**
 * Kartu pengetahuan: fakta dan rumus yang tidak boleh dikarang model. Hanya kartu terverifikasi yang boleh
 * jadi satu-satunya sumber; kartu yang belum terverifikasi diberi tanda dan ikut dilaporkan ke pengguna.
 */
data class Card(val id: String, val title: String, val text: String, val verified: Boolean, val source: String)

object Cards {

    val ALL: List<Card> = listOf(
        Card(
            id = "prayer-times",
            title = "Waktu sholat (rumus astronomi)",
            verified = true,
            source = "praytimes.org/calculation (rumus waktu dan posisi matahari); bentuk arccos T() dan A() adalah rumus baku di halaman yang sama",
            text = """
                Inputs: lat (phi, degrees), lon (Lng, degrees east positive), date, timeZone (hours from UTC).
                Sun position for Julian date jd: d = jd - 2451545.0; g = 357.529 + 0.98560028*d; q = 280.459 + 0.98564736*d;
                L = q + 1.915*sin(g) + 0.020*sin(2g); e = 23.439 - 0.00000036*d; RA = atan2(cos(e)*sin(L), cos(L))/15;
                D = asin(sin(e)*sin(L)) (declination); EqT = q/15 - RA (equation of time, hours). Angles in degrees; normalize q, g, L to 0..360.
                Dhuhr = 12 + timeZone - Lng/15 - EqT.
                T(a) = (1/15) * acos((-sin(a) - sin(phi)*sin(D)) / (cos(phi)*cos(D)))   (hours)
                A(t) = (1/15) * acos((sin(acot(t + tan(|phi - D|))) - sin(phi)*sin(D)) / (cos(phi)*cos(D)))
                Sunrise = Dhuhr - T(0.833); Sunset = Dhuhr + T(0.833).
                Fajr = Dhuhr - T(fajrAngle); Isha = Dhuhr + T(ishaAngle).
                Asr = Dhuhr + A(1) for Shafi'i, Maliki, Hanbali, Ja'fari; A(2) for Hanafi.
                Maghrib = Sunset (a 1-3 minute precaution is common).
                Method angles (Fajr/Isha): Muslim World League 18/17; ISNA 15/15; Egyptian 19.5/17.5; Karachi 18/18; Singapore 20/18.
                Indonesia (Kemenag RI): Fajr 20, Isha 18 (NOT verified against an official source: show it as a selectable method and tell the user to confirm).
                Times are decimal hours; convert to HH:MM. High latitudes (|lat| > 48) need special handling: state the limitation, do not guess.
            """.trimIndent()
        ),
        Card(
            id = "qibla",
            title = "Arah kiblat (bearing ke Ka'bah)",
            verified = false,
            source = "rumus great-circle bearing baku; koordinat Ka'bah 21.4225 N, 39.8262 E belum diverifikasi dari sumber di sesi ini",
            text = """
                Kaaba: lat2 = 21.4225, lon2 = 39.8262 (degrees). User: lat1, lon1.
                dLon = lon2 - lon1 (radians).
                bearing = atan2(sin(dLon)*cos(lat2), cos(lat1)*sin(lat2) - sin(lat1)*cos(lat2)*cos(dLon)); degrees = (bearing*180/pi + 360) mod 360.
                The result is degrees clockwise from TRUE north. A phone compass reports magnetic heading; do not claim accuracy beyond what the sensor gives and tell the user to calibrate.
            """.trimIndent()
        )
    )

    private val byId = ALL.associateBy { it.id }

    fun get(id: String): Card? = byId[id.removePrefix("kartu:")]

    fun known(id: String): Boolean = get(id) != null

    /** Teks kartu untuk dimasukkan ke tiket; id tak dikenal dilewati (validator sudah menolaknya). */
    fun factsFor(ids: List<String>): List<String> =
        ids.mapNotNull { get(it) }.map { "${it.title}${if (it.verified) "" else " [BELUM TERVERIFIKASI]"}:\n${it.text}" }

    /** Daftar singkat untuk prompt blueprint. */
    fun catalog(): String = ALL.joinToString("\n") { "- ${it.id}: ${it.title}" }
}
