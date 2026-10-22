package com.pixelforge.routing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Meteran jatah per provider. Menghitung permintaan sendiri (per menit dan per hari UTC), lalu
 * dikoreksi dari header respons bila provider mengirimnya. Murni Kotlin supaya bisa diuji unit.
 *
 * Angka batas bisa salah karena jatah gratis sering berubah; karena itu 429 dari server selalu
 * menang atas hitungan lokal: provider ditahan sampai waktu yang dilaporkan server.
 */
class QuotaMeter(private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class Limits(val perMinute: Int?, val perDay: Int?)

    data class Snapshot(
        val provider: String,
        val usedMinute: Int,
        val usedDay: Int,
        val perMinute: Int?,
        val perDay: Int?,
        /** Sisa permintaan menurut header terakhir dari server (null = server tidak melaporkan). */
        val reportedRemaining: Int?,
        /** Waktu (epoch ms) provider boleh dipakai lagi, null kalau tidak sedang ditahan. */
        val blockedUntil: Long?
    ) {
        /** Sisa jatah harian menurut hitungan lokal; null kalau batas harian tidak diketahui. */
        val remainingDay: Int? get() = perDay?.let { (it - usedDay).coerceAtLeast(0) }
    }

    sealed class Verdict {
        object Ok : Verdict()
        /** Tunggu sebentar (jendela per menit penuh atau ditahan server). */
        data class Wait(val ms: Long) : Verdict()
        /** Jatah harian habis; kembali normal pada [resetAtMs]. */
        data class DayExhausted(val resetAtMs: Long) : Verdict()
    }

    private class State {
        var limits = Limits(null, null)
        val minuteHits = ArrayDeque<Long>()
        var dayKey = -1L
        var usedDay = 0
        var reportedRemaining: Int? = null
        var blockedUntil: Long = 0L
    }

    private val states = LinkedHashMap<String, State>()
    private val _snapshots = MutableStateFlow<List<Snapshot>>(emptyList())
    val snapshots: StateFlow<List<Snapshot>> = _snapshots.asStateFlow()

    /** Dipanggil setiap kali ada perubahan (untuk menyimpan ke disk). */
    @Volatile var onChange: (() -> Unit)? = null

    @Synchronized
    fun setLimits(provider: String, limits: Limits) {
        stateOf(provider).limits = limits
        publish(notify = false)
    }

    /** Catat satu permintaan yang dikirim ke provider (berhasil atau gagal, keduanya memakai jatah). */
    @Synchronized
    fun record(provider: String) {
        val s = stateOf(provider)
        val now = clock()
        roll(s, now)
        s.minuteHits.addLast(now)
        s.usedDay++
        publish()
    }

    /** Boleh dipanggil provider ini sekarang? Sekaligus menyegarkan tampilan meteran (jendela yang sudah lewat). */
    @Synchronized
    fun check(provider: String): Verdict = checkNow(provider).also { publish(notify = false) }

    private fun checkNow(provider: String): Verdict {
        val s = stateOf(provider)
        val now = clock()
        roll(s, now)
        val perDay = s.limits.perDay
        if (perDay != null && s.usedDay >= perDay) return Verdict.DayExhausted(nextDayStart(now))
        if (s.blockedUntil > now) return Verdict.Wait(s.blockedUntil - now)
        val perMinute = s.limits.perMinute
        if (perMinute != null && s.minuteHits.size >= perMinute) {
            val wait = s.minuteHits.first() + MINUTE - now
            if (wait > 0) return Verdict.Wait(wait)
        }
        return Verdict.Ok
    }

    /** Server menjawab 429: tahan provider ini sampai waktu yang dilaporkan (atau 60 detik). */
    @Synchronized
    fun noteRateLimited(provider: String, retryAfterMs: Long?) {
        val s = stateOf(provider)
        s.blockedUntil = clock() + (retryAfterMs ?: MINUTE).coerceAtLeast(1000L)
        publish()
    }

    /** Baca header jatah bila ada. Nama header berbeda-beda antar provider, jadi dicoba beberapa. */
    @Synchronized
    fun observeHeaders(provider: String, header: (String) -> String?) {
        val s = stateOf(provider)
        val remaining = REMAINING_HEADERS.firstNotNullOfOrNull { header(it)?.trim()?.toDoubleOrNull() }?.toInt()
        if (remaining != null) {
            s.reportedRemaining = remaining
            if (remaining <= 0) {
                val raw = RESET_HEADERS.firstNotNullOfOrNull { header(it)?.trim()?.toDoubleOrNull() }
                val now = clock()
                // Angka sangat besar = waktu epoch dalam milidetik (gaya OpenRouter); angka kecil = detik lagi.
                val waitMs = when {
                    raw == null -> MINUTE
                    raw > 1e11 -> raw.toLong() - now
                    else -> (raw * 1000).toLong()
                }
                s.blockedUntil = now + waitMs.coerceIn(1000L, DAY)
            }
            publish()
        }
    }

    /** Waktu jatah harian provider kembali (awal hari UTC berikutnya). */
    fun resetAt(): Long = nextDayStart(clock())

    // ---------- simpan / muat ----------

    /** Hanya hitungan harian yang disimpan (jendela per menit tidak perlu bertahan). */
    @Synchronized
    fun export(): String =
        states.entries.joinToString("\n") { (name, s) -> "${name.replace("|", " ")}|${s.dayKey}|${s.usedDay}" }

    @Synchronized
    fun restore(data: String?) {
        if (data.isNullOrBlank()) return
        for (line in data.lines()) {
            val parts = line.split("|")
            if (parts.size != 3) continue
            val key = parts[1].toLongOrNull() ?: continue
            val used = parts[2].toIntOrNull() ?: continue
            val s = stateOf(parts[0])
            s.dayKey = key
            s.usedDay = used
        }
        publish(notify = false)
    }

    // ---------- internal ----------

    private fun stateOf(provider: String) = states.getOrPut(provider) { State() }

    private fun roll(s: State, now: Long) {
        val key = now / DAY
        if (s.dayKey != key) {
            // Penahanan hanya dibuang saat hari benar-benar berganti, bukan saat state baru dibuat.
            if (s.dayKey != -1L) s.blockedUntil = 0L
            s.dayKey = key
            s.usedDay = 0
        }
        while (s.minuteHits.isNotEmpty() && s.minuteHits.first() <= now - MINUTE) s.minuteHits.removeFirst()
    }

    private fun nextDayStart(now: Long): Long = (now / DAY + 1) * DAY

    private fun publish(notify: Boolean = true) {
        val now = clock()
        _snapshots.value = states.map { (name, s) ->
            roll(s, now)
            Snapshot(
                provider = name,
                usedMinute = s.minuteHits.size,
                usedDay = s.usedDay,
                perMinute = s.limits.perMinute,
                perDay = s.limits.perDay,
                reportedRemaining = s.reportedRemaining,
                blockedUntil = s.blockedUntil.takeIf { it > now }
            )
        }
        if (notify) onChange?.invoke()
    }

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L

        /** Batas gratis OpenRouter untuk model :free (dari dokumen OpenRouter; bisa berubah). */
        val OPENROUTER_FREE = Limits(perMinute = 20, perDay = 50)
        /** Setelah pernah membeli kredit minimal $10. */
        val OPENROUTER_FREE_WITH_CREDITS = Limits(perMinute = 20, perDay = 1000)

        private val REMAINING_HEADERS = listOf("x-ratelimit-remaining-requests", "x-ratelimit-remaining")
        private val RESET_HEADERS = listOf("x-ratelimit-reset-requests", "x-ratelimit-reset")
    }
}
