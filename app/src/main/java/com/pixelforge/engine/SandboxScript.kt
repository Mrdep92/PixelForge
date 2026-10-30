package com.pixelforge.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Membuat skrip uji yang dijalankan di dalam halaman sandbox. Hasilnya: string JSON berisi daftar {label, ok, detail}. */
object SandboxScript {
    private const val TEMPLATE = """
(function () {
  var out = [];
  var checks = __CHECKS__;
  function eq(a, b, t) {
    if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= t;
    if (a === b) return true;
    if (a && b && typeof a === 'object' && typeof b === 'object') {
      if (Array.isArray(a) !== Array.isArray(b)) return false;
      var ka = Object.keys(a), kb = Object.keys(b);
      if (ka.length !== kb.length) return false;
      for (var i = 0; i < ka.length; i++) { if (!eq(a[ka[i]], b[ka[i]], t)) return false; }
      return true;
    }
    return false;
  }
  function add(label, ok, detail) { out.push({ label: label, ok: ok, detail: detail || '' }); }
  checks.forEach(function (c) {
    try {
      if (c.k === 'EXISTS') {
        var n = document.querySelectorAll(c.s).length;
        add(c.l, n > 0, n > 0 ? '' : 'tidak ada elemen ' + c.s);
      } else if (c.k === 'TEXT') {
        var el = document.querySelector(c.s);
        var tx = el ? (el.textContent || '') : null;
        var okT = tx !== null && tx.indexOf(c.t) >= 0;
        add(c.l, okT, okT ? '' : (tx === null ? 'tidak ada elemen ' + c.s : 'teks tidak memuat "' + c.t + '"'));
      } else if (c.k === 'CALL') {
        var parts = c.f.split('.');
        var owner = null;
        var f = (0, eval)(parts[0]);
        for (var j = 1; j < parts.length; j++) { owner = f; f = f[parts[j]]; }
        if (typeof f !== 'function') { add(c.l, false, 'bukan fungsi: ' + c.f); return; }
        var r = f.apply(owner, c.a);
        if (r && typeof r.then === 'function') { add(c.l, false, 'fungsi async tidak didukung: ' + c.f); return; }
        var rr = JSON.parse(JSON.stringify(r === undefined ? null : r));
        var ok = eq(rr, c.e, c.t);
        add(c.l, ok, ok ? '' : 'hasil ' + JSON.stringify(rr) + ', harapan ' + JSON.stringify(c.e));
      } else {
        add(c.l, false, 'jenis tidak dikenal: ' + c.k);
      }
    } catch (e) {
      add(c.l, false, 'galat: ' + ((e && e.message) || e));
    }
  });
  return JSON.stringify(out);
})()
"""

    fun build(plan: TestPlan): String {
        val arr = JsonArray()
        for (c in plan.checks) {
            if (c.kind == Check.CALL && !PlanValidator.isSafeIdent(c.fn)) continue // tidak pernah disisipkan ke skrip
            arr.add(JsonObject().apply {
                addProperty("k", c.kind); addProperty("l", c.label)
                addProperty("s", c.selector)
                if (c.kind == Check.CALL) {
                    addProperty("f", c.fn)
                    add("a", JsonParser.parseString(c.args)); add("e", JsonParser.parseString(c.expect))
                    addProperty("t", c.tolerance)
                } else addProperty("t", c.text)
            })
        }
        return TEMPLATE.replace("__CHECKS__", arr.toString())
    }
}
