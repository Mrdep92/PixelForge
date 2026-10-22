package com.pixelforge.routing

/** Aturan privasi: project privat tidak mengirim kode ke provider gratis tanpa izin pemilik. */
object Privacy {

    data class Result(val providers: List<Provider>)

    /**
     * Project publik, atau privat dengan izin → semua provider.
     * Project privat tanpa izin → hanya provider tepercaya, dan provider berbayar dengan model non-:free.
     * Daftar kosong berarti tidak ada jalur yang aman.
     */
    fun apply(providers: List<Provider>, isPrivate: Boolean, freeConsent: Boolean): Result {
        if (!isPrivate || freeConsent) return Result(providers)
        val allowed = providers.mapNotNull { p ->
            when {
                p.trusted -> p
                !p.freeOnly -> {
                    val paid = p.models.filterNot { FreeModels.isFree(it) }
                    if (paid.isEmpty()) null else p.copy(models = paid)
                }
                else -> null
            }
        }
        return Result(allowed)
    }
}
