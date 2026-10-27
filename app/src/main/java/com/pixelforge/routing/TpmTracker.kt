package com.pixelforge.routing

/**
 * Pelacak token per menit (jendela geser 60 detik) per provider. Dipakai untuk provider yang membatasi TPM
 * (mis. Groq) supaya permintaan yang pasti ditolak tidak dikirim. Sinyal dari server tetap yang utama.
 * Murni Kotlin; ide dari agent Termux v0.1.58 (tpm.py).
 */
class TpmTracker(
    private val limits: Map<String, Int> = emptyMap(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val events = HashMap<String, ArrayDeque<Pair<Long, Int>>>()

    private fun prune(provider: String, now: Long): ArrayDeque<Pair<Long, Int>> {
        val ev = events.getOrPut(provider) { ArrayDeque() }
        while (ev.isNotEmpty() && now - ev.first().first >= WINDOW) ev.removeFirst()
        return ev
    }

    @Synchronized fun record(provider: String, tokens: Int) {
        events.getOrPut(provider) { ArrayDeque() }.addLast(clock() to tokens)
    }

    /** Milidetik sampai [est] token boleh dikirim tanpa melewati 80% batas. 0 = langsung; [WINDOW] = tidak akan muat. */
    @Synchronized fun waitNeededMs(provider: String, est: Int): Long {
        val lim = limits[provider] ?: return 0L
        val now = clock()
        val ev = prune(provider, now)
        val budget = (lim * SOFT).toInt()
        if (est > budget) return WINDOW
        val used = ev.sumOf { it.second }
        if (used + est <= budget) return 0L
        var freed = 0
        for ((ts, t) in ev) {
            freed += t
            if (used - freed + est <= budget) return maxOf(0L, ts + WINDOW - now)
        }
        return WINDOW
    }

    companion object {
        const val WINDOW = 60_000L
        const val SOFT = 0.8

        /** Perkiraan kasar: 1 token ≈ 4 karakter. */
        fun estimate(chars: Int, reserveForReply: Int = 1000): Int = chars / 4 + reserveForReply

        private val TPM_MARK = Regex("tokens per minute|\\bTPM\\b", RegexOption.IGNORE_CASE)
        private val RPM_MARK = Regex("requests per minute|\\bRPM\\b", RegexOption.IGNORE_CASE)
        private val RETRY_IN = Regex("try again in ([\\d.]+)\\s*(ms|s)\\b")

        /** 429 yang menyebut token per menit boleh ditunggu; RPM/harian tidak. */
        fun isTpmError(err: String) = TPM_MARK.containsMatchIn(err)
        fun isRpmError(err: String) = RPM_MARK.containsMatchIn(err) && !isTpmError(err)

        /** "try again in 3.17s" / "in 250ms" dari pesan error provider. */
        fun parseRetryMs(err: String): Long? {
            val m = RETRY_IN.find(err) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return if (m.groupValues[2] == "ms") v.toLong() else (v * 1000).toLong()
        }
    }
}
