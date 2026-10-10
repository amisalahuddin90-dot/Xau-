package com.free.smcict

import java.time.*

data class C(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)
data class Sig(val dir: Int, val score: Int, val entry: Double, val sl: Double, val tp: Double)
data class Result(val price: Double, val smc: Sig?, val ict: Sig?, val comb: Sig?, val note: String)
data class Fvg(val i: Int, val bull: Boolean, val lo: Double, val hi: Double)
data class Lvl(val name: String, val p: Double, val high: Boolean)

/** All logic is written for LONGS. Shorts = same code on mirrored (price * -1) data. */
object Engine {
    private val ny = ZoneId.of("America/New_York")
    private const val MIN = 55        // minimum score per model
    private const val MINRR = 1.5     // minimum reward:risk

    fun atr(a: List<C>, n: Int = 14): Double {
        val s = a.takeLast(n + 1); var sum = 0.0
        for (i in 1 until s.size) sum += maxOf(s[i].h - s[i].l, Math.abs(s[i].h - s[i - 1].c), Math.abs(s[i].l - s[i - 1].c))
        return sum / (s.size - 1)
    }

    fun swings(a: List<C>, k: Int = 3): Pair<List<Int>, List<Int>> {
        val hi = ArrayList<Int>(); val lo = ArrayList<Int>()
        for (i in k until a.size - k) {
            var h = true; var l = true
            for (j in 1..k) {
                if (a[i].h <= a[i - j].h || a[i].h <= a[i + j].h) h = false
                if (a[i].l >= a[i - j].l || a[i].l >= a[i + j].l) l = false
            }
            if (h) hi.add(i)
            if (l) lo.add(i)
        }
        return hi to lo
    }

    fun trend(a: List<C>): Int {
        val (hi, lo) = swings(a)
        if (hi.size < 2 || lo.size < 2) return 0
        val hh = a[hi[hi.size - 1]].h > a[hi[hi.size - 2]].h
        val hl = a[lo[lo.size - 1]].l > a[lo[lo.size - 2]].l
        return if (hh && hl) 1 else if (!hh && !hl) -1 else 0
    }

    private fun mirror(a: List<C>) = a.map { C(it.t, -it.o, -it.l, -it.h, -it.c) }

    private fun fvgs(a: List<C>, at: Double): List<Fvg> {
        val r = ArrayList<Fvg>()
        for (i in 2 until a.size) {
            if (Math.abs(a[i - 1].c - a[i - 1].o) < 0.7 * at) continue   // needs displacement
            if (a[i - 2].h < a[i].l && a[i].l - a[i - 2].h >= 0.1 * at) r.add(Fvg(i, true, a[i - 2].h, a[i].l))
            if (a[i - 2].l > a[i].h && a[i - 2].l - a[i].h >= 0.1 * at) r.add(Fvg(i, false, a[i].h, a[i - 2].l))
        }
        return r
    }

    // ---- ICT time-based levels (New York time): Asia 18-00, London 00-06, previous day ----
    private fun rng(a: List<C>, f: ZonedDateTime, e: ZonedDateTime): Pair<Double, Double>? {
        val s = a.filter { it.t >= f.toInstant().toEpochMilli() && it.t < e.toInstant().toEpochMilli() }
        return if (s.size < 4) null else s.maxOf { it.h } to s.minOf { it.l }
    }

    private fun levels(a: List<C>): List<Lvl> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(a.last().t), ny)
        val d = now.plusHours(6).toLocalDate()
        fun at(dd: LocalDate, h: Int) = dd.atTime(h, 0).atZone(ny)
        val out = ArrayList<Lvl>()
        fun add(n: String, r: Pair<Double, Double>?, end: ZonedDateTime) {
            if (r != null && !end.isAfter(now)) { out.add(Lvl("$n high", r.first, true)); out.add(Lvl("$n low", r.second, false)) }
        }
        add("Asia", rng(a, at(d.minusDays(1), 18), at(d, 0)), at(d, 0))
        add("London", rng(a, at(d, 0), at(d, 6)), at(d, 6))
        val pd = now.toLocalDate().minusDays(1)
        add("PrevDay", rng(a, at(pd, 0), at(pd.plusDays(1), 0)), at(pd.plusDays(1), 0))
        return out
    }

    // ---- SMC: HTF trend + liquidity sweep + BOS/CHoCH + order block + discount ----
    private fun smcLong(a: List<C>, ht: Int, hpos: Double, price: Double): Sig? {
        val n = a.size; val at = atr(a); val (hi, lo) = swings(a)
        var brk = -1
        for (sh in hi.takeLast(3).reversed()) {
            val b = (sh + 1 until n).firstOrNull { a[it].c > a[sh].h } ?: continue
            if (n - 1 - b <= 30) { brk = b; break }
        }
        if (brk < 0) return null
        val sl = lo.lastOrNull { it < brk } ?: return null
        val ob = (brk - 1 downTo sl).firstOrNull { a[it].c < a[it].o } ?: return null
        val oLo = a[ob].l; val oHi = a[ob].h
        if (price < oLo || (brk + 1 until n).any { a[it].c < oLo }) return null   // OB broken
        val entry = (oLo + oHi) / 2
        var s = 25                                                  // structure break
        val k = lo.indexOf(sl)
        if (k > 0 && a[sl].l < a[lo[k - 1]].l) s += 20              // sell-side liquidity swept
        s += if (ht == 1) 25 else if (ht == 0) 10 else 0            // higher-timeframe trend
        if (hpos < 0.5) s += 15                                     // discount
        if (price - entry <= at) s += 15                            // price near the OB
        val stop = minOf(oLo, a[sl].l) - 0.25 * at
        val risk = entry - stop
        if (risk <= 0) return null
        val tgt = hi.map { a[it].h }.filter { it > entry + MINRR * risk }.minOrNull() ?: (entry + 2 * risk)
        if (price >= tgt) return null
        return Sig(1, s, entry, stop, tgt)
    }

    // ---- ICT: key-level sweep + FVG / inverted FVG confirmation + kill zone + level target ----
    private fun ictLong(a: List<C>, lv: List<Lvl>, price: Double, kz: Boolean): Sig? {
        val n = a.size; val at = atr(a)
        var s = -1
        for (i in maxOf(0, n - 24) until n)
            for (L in lv)
                if (!L.high && a[i].l < L.p && (i until minOf(n, i + 4)).any { a[it].c > L.p } && i > s) s = i
        if (s < 0) return null
        val lowest = (s until n).minOf { a[it].l }
        val fv = fvgs(a, at)
        var z: Pair<Double, Double>? = fv.filter { it.bull && it.i >= s && (it.i + 1 until n).none { j -> a[j].c < it.lo } }
            .lastOrNull()?.let { it.lo to it.hi }
        if (z == null) z = fv.filter { !it.bull && it.i >= s - 10 && (it.i + 1 until n).any { j -> j >= s && a[j].c > it.hi } }
            .lastOrNull()?.let { it.lo to it.hi }
        val zz = z ?: return null
        if (price < zz.first) return null
        val entry = (zz.first + zz.second) / 2
        var sc = 55
        if (kz) sc += 15
        if (price - entry <= at) sc += 15
        val stop = lowest - 0.25 * at
        val risk = entry - stop
        if (risk <= 0) return null
        val t1 = lv.filter { it.high && it.p >= entry + MINRR * risk }.map { it.p }.minOrNull()
        if (t1 != null) sc += 15
        val tgt = t1 ?: (entry + 2 * risk)
        if (price >= tgt) return null
        return Sig(1, sc, entry, stop, tgt)
    }

    private fun fix(s: Sig, d: Int) = if (d == 1) s else Sig(-1, s.score, -s.entry, -s.sl, -s.tp)

    fun analyze(m15: List<C>, h1: List<C>): Result {
        val a = m15.dropLast(1); val h = h1.dropLast(1)       // drop the still-forming candle
        val price = m15.last().c
        val tr = trend(h)
        val seg = h.takeLast(60)
        val top = seg.maxOf { it.h }; val bot = seg.minOf { it.l }
        val pos = (price - bot) / maxOf(top - bot, 1e-9)
        val lv = levels(a)
        val z = ZonedDateTime.ofInstant(Instant.ofEpochMilli(a.last().t), ny)
        val m = z.hour * 60 + z.minute
        val kz = m in 120..299 || m in 510..689                  // London 02-05, New York 08:30-11:30 ET
        var bs: Sig? = null; var bi: Sig? = null
        for (d in intArrayOf(1, -1)) {
            val x = if (d == 1) a else mirror(a)
            val l2 = if (d == 1) lv else lv.map { Lvl(it.name, -it.p, !it.high) }
            val s1 = smcLong(x, tr * d, if (d == 1) pos else 1 - pos, price * d)?.let { fix(it, d) }
            val s2 = ictLong(x, l2, price * d, kz)?.let { fix(it, d) }
            if (s1 != null && (bs == null || s1.score > bs.score)) bs = s1
            if (s2 != null && (bi == null || s2.score > bi.score)) bi = s2
        }
        val smc = bs?.takeIf { it.score >= MIN }
        val ict = bi?.takeIf { it.score >= MIN }
        var comb: Sig? = null; var note = ""
        if (smc != null && ict != null) {
            if (smc.dir == ict.dir) {
                val d = smc.dir
                val e = (smc.entry + ict.entry) / 2
                val sl = if (d == 1) minOf(smc.sl, ict.sl) else maxOf(smc.sl, ict.sl)   // safer stop
                val tp = if (d == 1) minOf(smc.tp, ict.tp) else maxOf(smc.tp, ict.tp)   // nearer target
                val risk = (e - sl) * d
                val rr = if (risk > 0) (tp - e) * d / risk else 0.0
                if (rr >= MINRR) comb = Sig(d, (smc.score + ict.score) / 2, e, sl, tp) else note = "Both agree, but reward:risk is too low"
            } else note = "SMC and ICT disagree - stay out"
        } else if (smc != null || ict != null) note = "Only one model sees a setup - wait"
        else if (!kz) note = "Outside kill zones - wait"
        return Result(price, smc, ict, comb, note)
    }
}
