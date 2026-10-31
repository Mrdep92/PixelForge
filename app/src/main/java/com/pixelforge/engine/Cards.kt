package com.pixelforge.engine

/**
 * Kartu pengetahuan: fakta dan rumus yang tidak boleh dikarang model. Hanya kartu terverifikasi yang boleh
 * jadi satu-satunya sumber; kartu yang belum terverifikasi diberi tanda dan ikut dilaporkan ke pengguna.
 */
data class Card(
    val id: String,
    val title: String,
    val text: String,
    val verified: Boolean,
    val source: String,
    /** Fungsi yang WAJIB ada di file pemilik kartu (ditambahkan ke exports oleh kode). */
    val requires: List<String> = emptyList(),
    /** Uji bernilai acuan terverifikasi yang otomatis masuk rencana tes. */
    val vectors: List<Check> = emptyList()
)

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
                High latitudes (|lat| > 48) need special handling: state the limitation, do not guess.
                REQUIRED FUNCTIONS (exact names, global scope, plain <script>, no modules):
                - prayerTimes(lat, lon, year, month, day, tz, fajrAngle, ishaAngle) returns an object with EXACTLY these keys, values in DECIMAL HOURS (0..24): fajr, sunrise, dhuhr, asr, maghrib, isha. Use the Julian date of year/month/day (UTC noon). Asr uses A(1).
                - formatHHMM(hours) returns a zero-padded "HH:MM" string: round to the nearest minute, carry 60 minutes into the hour, wrap 24h to 00. formatHHMM(4.5) = "04:30".
            """.trimIndent(),
            requires = listOf(
                "prayerTimes(lat, lon, year, month, day, tz, fajrAngle, ishaAngle)",
                "formatHHMM(hours)"
            ),
            vectors = listOf(
                Check(
                    Check.CALL, "kartu: waktu sholat Jakarta 20 Mar 2026 (Kemenag 20/18)", fn = "prayerTimes",
                    args = "[-6.2088,106.8456,2026,3,20,7,20,18]",
                    expect = """{"fajr":4.659,"sunrise":5.945,"dhuhr":12.001,"asr":15.177,"maghrib":18.058,"isha":19.209}""",
                    tolerance = 0.05, verified = true
                ),
                Check(
                    Check.CALL, "kartu: waktu sholat Jakarta 21 Jun 2026 (Kemenag 20/18)", fn = "prayerTimes",
                    args = "[-6.2088,106.8456,2026,6,21,7,20,18]",
                    expect = """{"fajr":4.632,"sunrise":6.027,"dhuhr":11.907,"asr":15.273,"maghrib":17.788,"isha":19.038}""",
                    tolerance = 0.05, verified = true
                ),
                Check(Check.CALL, "kartu: format jam 4.5", fn = "formatHHMM", args = "[4.5]", expect = "\"04:30\"", verified = true),
                Check(Check.CALL, "kartu: format jam 11.9833", fn = "formatHHMM", args = "[11.9833]", expect = "\"11:59\"", verified = true),
                Check(Check.CALL, "kartu: format jam 23.9999", fn = "formatHHMM", args = "[23.9999]", expect = "\"00:00\"", verified = true)
            )
        ),
        Card(
            id = "qibla",
            title = "Arah kiblat (bearing ke Ka'bah)",
            verified = true,
            source = "rumus great-circle bearing baku; koordinat Ka'bah 21.4225 N, 39.8262 E (nilai yang lazim dipakai); hasil rumus cocok dengan arah kiblat yang dikenal: Jakarta ~295, London ~119, New York ~58, Sydney ~277",
            text = """
                Kaaba: lat2 = 21.4225, lon2 = 39.8262 (degrees). User: lat1, lon1.
                dLon = lon2 - lon1 (radians).
                bearing = atan2(sin(dLon)*cos(lat2), cos(lat1)*sin(lat2) - sin(lat1)*cos(lat2)*cos(dLon)); degrees = (bearing*180/pi + 360) mod 360.
                The result is degrees clockwise from TRUE north. A phone compass reports magnetic heading; do not claim accuracy beyond what the sensor gives and tell the user to calibrate.
                REQUIRED FUNCTION: qiblaBearing(lat, lon) returns degrees (number, 0..360) clockwise from true north.
            """.trimIndent(),
            requires = listOf("qiblaBearing(lat, lon)"),
            vectors = listOf(
                Check(Check.CALL, "kartu: kiblat Jakarta", fn = "qiblaBearing", args = "[-6.2088,106.8456]", expect = "295.15", tolerance = 0.5, verified = true),
                Check(Check.CALL, "kartu: kiblat London", fn = "qiblaBearing", args = "[51.5074,-0.1278]", expect = "118.99", tolerance = 0.5, verified = true),
                Check(Check.CALL, "kartu: kiblat New York", fn = "qiblaBearing", args = "[40.7128,-74.006]", expect = "58.48", tolerance = 0.5, verified = true)
            )
        )
    )

    private val byId = ALL.associateBy { it.id }

    fun get(id: String): Card? = byId[id.removePrefix("kartu:")]

    fun known(id: String): Boolean = get(id) != null

    /** Teks kartu untuk dimasukkan ke tiket; id tak dikenal dilewati (validator sudah menolaknya). */
    fun factsFor(ids: List<String>): List<String> =
        ids.mapNotNull { get(it) }.map { "${it.title}${if (it.verified) "" else " [BELUM TERVERIFIKASI]"}:\n${it.text}" }

    /** Daftar singkat untuk prompt blueprint. */
    fun catalog(): String = ALL.joinToString("\n") { c ->
        "- ${c.id}: ${c.title}" + if (c.requires.isNotEmpty()) " (adds required functions: ${c.requires.joinToString("; ")})" else ""
    }
}
