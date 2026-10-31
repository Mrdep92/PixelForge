package com.pixelforge.engine

/** Kontrak yang ditegakkan kode setelah blueprint dibuat: fungsi wajib dari kartu, uji bernilai acuan, uji elemen terisi. */
object Contracts {
    private fun fnName(sig: String) = sig.substringBefore('(').trim()

    /** Menambahkan fungsi wajib kartu ke exports file pemilik kartu (menggantikan tanda tangan buatan model bila namanya sama). */
    fun withCardExports(spec: AppSpec): AppSpec = spec.copy(
        files = spec.files.map { f ->
            val req = f.cards.distinct().flatMap { Cards.get(it)?.requires.orEmpty() }
            if (req.isEmpty()) f
            else {
                val names = req.map { fnName(it) }.toSet()
                f.copy(exports = f.exports.filterNot { fnName(it) in names } + req)
            }
        }
    )

    /** Rencana akhir: uji model + uji vektor kartu + uji "elemen yang harus terisi". */
    fun finalPlan(spec: AppSpec, plan: TestPlan?): TestPlan {
        val out = plan?.checks.orEmpty().toMutableList()
        val labels = out.map { it.label }.toMutableSet()
        val cards = spec.files.flatMap { it.cards }.distinct().mapNotNull { Cards.get(it) }
        for (c in cards) for (v in c.vectors) if (labels.add(v.label)) out += v
        for (d in spec.dom) {
            if (d.by.isNotBlank() && !d.lazy) {
                val label = "terisi: #${d.id}"
                if (labels.add(label)) out += Check(Check.NONEMPTY, label, selector = "#${d.id}")
            }
        }
        return TestPlan(out)
    }
}
