package com.pixelforge.routing

/**
 * Membuang rahasia (API key, token, kunci pribadi) dari teks sebelum dikirim ke model.
 * Berlaku untuk semua project dan semua provider.
 */
object SecretFilter {
    const val MASK = "[REDACTED]"

    private val patterns: List<Regex> = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("\\bsk-[A-Za-z0-9_\\-]{20,}"),
        Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}"),
        Regex("\\bAIza[0-9A-Za-z_\\-]{35}"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}"),
        Regex("\\bgsk_[A-Za-z0-9]{20,}")
    )

    /** `api_key = "abc123..."`: hanya nilai yang tampak seperti rahasia (panjang, ada angkanya) yang disamarkan. */
    private val assignment = Regex(
        "(?i)(\\b(?:api[_-]?key|secret|token|passwd|password|private[_-]?key)\\w*\\s*[:=]\\s*[\"']?)" +
            "((?=[A-Za-z0-9_\\-+/=.]*\\d)[A-Za-z0-9_\\-+/=.]{16,})"
    )

    fun redact(text: String): String {
        var out = text
        for (p in patterns) out = p.replace(out, MASK)
        return assignment.replace(out) { it.groupValues[1] + MASK }
    }
}
