package com.mostafa.smartvision

import java.io.ByteArrayOutputStream
import java.util.Random

// موسيقى ومؤثرات صوتية بيئية تُولَّد بالكود نفسه (بدون إنترنت ولا ملفات جاهزة)
object Fx {
    class Item(val id: String, val label: String, val group: String)

    val MUSIC = listOf(
        Item("dua_sakina", "أدعية: سكينة", "موسيقى الأدعية والروحانيات"),
        Item("dua_khushu", "أدعية: خشوع", "موسيقى الأدعية والروحانيات"),
        Item("dua_noor", "أدعية: نور", "موسيقى الأدعية والروحانيات"),
        Item("dua_ney", "أدعية: ناي روحاني", "موسيقى الأدعية والروحانيات"),
        Item("dua_rahma", "أدعية: رجاء ورحمة", "موسيقى الأدعية والروحانيات"),
        Item("dua_night", "أدعية: ليل وقيام", "موسيقى الأدعية والروحانيات"),
        Item("calm_piano", "بيانو هادئ", "موسيقى هادئة"),
        Item("lullaby", "تهويدة صندوق الموسيقى", "موسيقى هادئة"),
        Item("happy", "موسيقى مرحة وخفيفة", "موسيقى مرحة"),
        Item("cinematic", "سينمائية مهيبة", "موسيقى درامية"),
        Item("suspense", "توتر وغموض", "موسيقى درامية")
    )

    val AMBS = listOf(
        Item("rain_light", "مطر خفيف", "مطر وعواصف"),
        Item("rain_heavy", "مطر غزير", "مطر وعواصف"),
        Item("rain_roof", "مطر على السقف", "مطر وعواصف"),
        Item("rain_wind", "مطر مع ريح", "مطر وعواصف"),
        Item("storm", "عاصفة رعدية", "مطر وعواصف"),
        Item("sea_waves", "أمواج البحر", "بحر وماء"),
        Item("sea_calm", "شاطئ هادئ", "بحر وماء"),
        Item("sea_storm", "بحر هائج", "بحر وماء"),
        Item("sea_gulls", "شاطئ ونوارس", "بحر وماء"),
        Item("stream", "جدول ماء", "بحر وماء"),
        Item("river", "نهر هادئ", "بحر وماء"),
        Item("waterfall", "شلال", "بحر وماء"),
        Item("cave", "كهف وصدى قطرات", "بحر وماء"),
        Item("wind", "رياح", "رياح وجبال"),
        Item("desert_wind", "رياح الصحراء", "رياح وجبال"),
        Item("mountain_birds", "جبل وطيور بعيدة", "رياح وجبال"),
        Item("birds_morning", "عصافير الصباح", "طيور وغابات"),
        Item("birds_garden", "طيور الحديقة", "طيور وغابات"),
        Item("forest", "غابة هادئة", "طيور وغابات"),
        Item("forest_rain", "غابة بعد المطر", "طيور وغابات"),
        Item("crickets", "ليل وصراصير", "ليل ونار"),
        Item("fire", "نار مدفأة", "ليل ونار")
    )

    fun musicLabel(id: String): String {
        for (m in MUSIC) if (m.id == id) return m.label
        return "بدون"
    }

    fun ambLabel(id: String): String {
        for (m in AMBS) if (m.id == id) return m.label
        return "بدون"
    }

    // ---------------- أدوات DSP بسيطة ----------------
    private fun noise(n: Int, r: Random): FloatArray {
        val a = FloatArray(n)
        for (i in 0 until n) a[i] = r.nextFloat() * 2f - 1f
        return a
    }

    private fun lp(x: FloatArray, a: Float): FloatArray {
        val y = FloatArray(x.size)
        var s = 0f
        for (i in x.indices) {
            s += a * (x[i] - s)
            y[i] = s
        }
        return y
    }

    private fun hp(x: FloatArray, a: Float): FloatArray {
        val l = lp(x, a)
        val y = FloatArray(x.size)
        for (i in x.indices) y[i] = x[i] - l[i]
        return y
    }

    private fun addTo(dst: FloatArray, src: FloatArray, g: Float) {
        val n = minOf(dst.size, src.size)
        for (i in 0 until n) dst[i] += src[i] * g
    }

    private fun peak(x: FloatArray): Float {
        var p = 0.0001f
        for (v in x) {
            val a = Math.abs(v)
            if (a > p) p = a
        }
        return p
    }

    private fun normalize(x: FloatArray, target: Float) {
        val g = target / peak(x)
        for (i in x.indices) x[i] *= g
    }

    // غلاف يتموّج ببطء: 1 - depth + depth * sin^2
    private fun swell(n: Int, rate: Int, period: Float, depth: Float, phase: Float): FloatArray {
        val e = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / rate.toFloat()
            val s = Math.sin(Math.PI * (t / period + phase)).toFloat()
            e[i] = (1f - depth) + depth * s * s
        }
        return e
    }

    private fun mul(x: FloatArray, e: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        for (i in x.indices) y[i] = x[i] * e[minOf(i, e.size - 1)]
        return y
    }

    // أحداث متقطعة: زقزقة طيور (تغيير تردد سريع)
    private fun chirps(n: Int, rate: Int, r: Random, perMin: Int, fLow: Float, fHigh: Float, gain: Float): FloatArray {
        val out = FloatArray(n)
        val secs = n.toFloat() / rate.toFloat()
        val count = maxOf(1, (perMin * secs / 60f).toInt())
        for (k in 0 until count) {
            val start = (r.nextFloat() * (secs - 0.6f).coerceAtLeast(0.1f) * rate).toInt()
            val notes = 1 + r.nextInt(4)
            var pos = start
            for (j in 0 until notes) {
                val dur = (0.05f + r.nextFloat() * 0.12f) * rate
                val f0 = fLow + r.nextFloat() * (fHigh - fLow)
                val f1 = f0 * (0.7f + r.nextFloat() * 0.8f)
                val len = dur.toInt()
                var ph = 0.0
                for (i in 0 until len) {
                    val idx = pos + i
                    if (idx >= n) break
                    val u = i.toFloat() / len.toFloat()
                    val f = f0 + (f1 - f0) * u
                    ph += 2.0 * Math.PI * f / rate
                    val env = Math.sin(Math.PI * u).toFloat()
                    out[idx] += (Math.sin(ph) * env * gain).toFloat()
                }
                pos += len + (0.03f * rate).toInt()
            }
        }
        return out
    }

    // نقرات عشوائية قصيرة (قطرات مطر أو طقطقة نار)
    private fun clicks(n: Int, rate: Int, r: Random, perSec: Float, gain: Float, bright: Float): FloatArray {
        val out = FloatArray(n)
        val secs = n.toFloat() / rate.toFloat()
        val count = (perSec * secs).toInt()
        for (k in 0 until count) {
            val pos = (r.nextFloat() * (n - 400)).toInt().coerceAtLeast(0)
            val len = 60 + r.nextInt(220)
            val amp = gain * (0.3f + r.nextFloat() * 0.7f)
            var prev = 0f
            for (i in 0 until len) {
                val idx = pos + i
                if (idx >= n) break
                val env = Math.exp(-6.0 * i / len).toFloat()
                val v = r.nextFloat() * 2f - 1f
                prev += bright * (v - prev)
                out[idx] += prev * env * amp
            }
        }
        return out
    }

    private fun thunder(n: Int, rate: Int, r: Random, everySec: Float, gain: Float): FloatArray {
        val out = FloatArray(n)
        val secs = n.toFloat() / rate.toFloat()
        val count = maxOf(1, (secs / everySec).toInt())
        for (k in 0 until count) {
            val pos = (r.nextFloat() * (secs * 0.8f) * rate).toInt()
            val len = (rate * (2.5f + r.nextFloat() * 2.5f)).toInt()
            val rum = lp(noise(len, r), 0.01f)
            for (i in 0 until len) {
                val idx = pos + i
                if (idx >= n) break
                val u = i.toFloat() / len.toFloat()
                val env = (if (u < 0.05f) u / 0.05f else Math.exp(-3.0 * (u - 0.05f)).toFloat())
                out[idx] += rum[i] * env * gain * 12f
            }
        }
        return out
    }

    private fun crickets(n: Int, rate: Int, r: Random, gain: Float): FloatArray {
        val out = FloatArray(n)
        var ph = 0.0
        val f = 4200.0 + r.nextInt(300)
        for (i in 0 until n) {
            val t = i.toFloat() / rate.toFloat()
            val cyc = (t * 2.2f) % 1f
            val burst = if (cyc < 0.5f) Math.sin(Math.PI * (cyc / 0.5f * 3.0)).toFloat() else 0f
            val gate = if (burst > 0f) burst else 0f
            ph += 2.0 * Math.PI * f / rate
            out[i] = (Math.sin(ph) * gate * gain).toFloat()
        }
        return out
    }

    private fun rainBed(n: Int, r: Random, gain: Float, dark: Float): FloatArray {
        val x = hp(lp(noise(n, r), dark), 0.02f)
        for (i in x.indices) x[i] *= gain
        return x
    }

    private fun windBed(n: Int, rate: Int, r: Random, gain: Float, speed: Float): FloatArray {
        val base = lp(noise(n, r), 0.008f * speed)
        normalize(base, 1f)
        val e = swell(n, rate, 9f / speed, 0.7f, r.nextFloat())
        val y = mul(base, e)
        for (i in y.indices) y[i] *= gain
        return y
    }

    private fun seaBed(n: Int, rate: Int, r: Random, period: Float, gain: Float): FloatArray {
        val body = lp(noise(n, r), 0.03f)
        normalize(body, 1f)
        val foam = hp(lp(noise(n, r), 0.25f), 0.05f)
        normalize(foam, 1f)
        val e = swell(n, rate, period, 0.85f, 0f)
        val y = FloatArray(n)
        for (i in 0 until n) y[i] = (body[i] * 0.8f + foam[i] * 0.25f * e[i]) * e[i] * gain
        return y
    }

    private fun streamBed(n: Int, rate: Int, r: Random, gain: Float, bright: Float): FloatArray {
        val a = lp(noise(n, r), bright)
        val b = lp(noise(n, r), bright * 0.4f)
        val y = FloatArray(n)
        val e = swell(n, rate, 3.3f, 0.35f, r.nextFloat())
        for (i in 0 until n) y[i] = (a[i] * 0.6f + b[i] * 0.8f) * e[i] * gain * 2.5f
        return y
    }

    private fun ambSegment(id: String, n: Int, rate: Int): FloatArray {
        val r = Random(id.hashCode().toLong())
        val out = FloatArray(n)
        when (id) {
            "rain_light" -> { addTo(out, rainBed(n, r, 0.22f, 0.5f), 1f); addTo(out, clicks(n, rate, r, 6f, 0.25f, 0.6f), 1f) }
            "rain_heavy" -> { addTo(out, rainBed(n, r, 0.5f, 0.7f), 1f); addTo(out, clicks(n, rate, r, 30f, 0.25f, 0.7f), 1f) }
            "rain_roof" -> { addTo(out, rainBed(n, r, 0.3f, 0.6f), 1f); addTo(out, clicks(n, rate, r, 25f, 0.5f, 0.9f), 1f) }
            "rain_wind" -> { addTo(out, rainBed(n, r, 0.3f, 0.6f), 1f); addTo(out, windBed(n, rate, r, 0.35f, 1.4f), 1f) }
            "storm" -> {
                addTo(out, rainBed(n, r, 0.4f, 0.7f), 1f)
                addTo(out, windBed(n, rate, r, 0.3f, 1.6f), 1f)
                addTo(out, thunder(n, rate, r, 7f, 0.9f), 1f)
            }
            "sea_waves" -> addTo(out, seaBed(n, rate, r, 8f, 0.55f), 1f)
            "sea_calm" -> addTo(out, seaBed(n, rate, r, 11f, 0.35f), 1f)
            "sea_storm" -> { addTo(out, seaBed(n, rate, r, 5f, 0.8f), 1f); addTo(out, windBed(n, rate, r, 0.3f, 1.5f), 1f) }
            "sea_gulls" -> { addTo(out, seaBed(n, rate, r, 9f, 0.35f), 1f); addTo(out, chirps(n, rate, r, 7, 1200f, 2200f, 0.12f), 1f) }
            "stream" -> addTo(out, streamBed(n, rate, r, 0.35f, 0.25f), 1f)
            "river" -> addTo(out, streamBed(n, rate, r, 0.28f, 0.12f), 1f)
            "waterfall" -> { addTo(out, streamBed(n, rate, r, 0.5f, 0.4f), 1f); addTo(out, rainBed(n, r, 0.25f, 0.5f), 1f) }
            "cave" -> { addTo(out, windBed(n, rate, r, 0.08f, 0.5f), 1f); addTo(out, clicks(n, rate, r, 0.7f, 0.5f, 0.25f), 1f) }
            "wind" -> addTo(out, windBed(n, rate, r, 0.5f, 1f), 1f)
            "desert_wind" -> addTo(out, windBed(n, rate, r, 0.6f, 0.7f), 1f)
            "mountain_birds" -> { addTo(out, windBed(n, rate, r, 0.3f, 1f), 1f); addTo(out, chirps(n, rate, r, 10, 2500f, 5000f, 0.1f), 1f) }
            "birds_morning" -> { addTo(out, windBed(n, rate, r, 0.06f, 0.8f), 1f); addTo(out, chirps(n, rate, r, 90, 2400f, 5200f, 0.12f), 1f) }
            "birds_garden" -> { addTo(out, windBed(n, rate, r, 0.08f, 0.8f), 1f); addTo(out, chirps(n, rate, r, 55, 1800f, 4200f, 0.14f), 1f) }
            "forest" -> { addTo(out, windBed(n, rate, r, 0.14f, 0.8f), 1f); addTo(out, chirps(n, rate, r, 18, 2000f, 4600f, 0.1f), 1f) }
            "forest_rain" -> { addTo(out, rainBed(n, r, 0.2f, 0.5f), 1f); addTo(out, chirps(n, rate, r, 12, 2200f, 4400f, 0.08f), 1f) }
            "crickets" -> { addTo(out, crickets(n, rate, r, 0.12f), 1f); addTo(out, windBed(n, rate, r, 0.05f, 0.7f), 1f) }
            "fire" -> { addTo(out, lp(noise(n, r), 0.02f), 0.6f); addTo(out, clicks(n, rate, r, 14f, 0.5f, 0.5f), 1f) }
            else -> {}
        }
        normalize(out, 0.9f)
        return out
    }

    // ---------------- الموسيقى ----------------
    private fun hz(root: Double, semis: Int): Double {
        return root * Math.pow(2.0, semis / 12.0)
    }

    // نغمة بمجموعة توافقيات وغلاف: attack بالثواني، decay (0 = مستمرة مع تلاشٍ في نهايتها)
    private fun tone(out: FloatArray, start: Int, len: Int, f: Double, amp: Float, rate: Int, attack: Float, decay: Float, vib: Float, harm: FloatArray) {
        if (start >= out.size || len <= 0) return
        val end = minOf(out.size, start + len)
        val att = maxOf(1f, attack * rate)
        val rel = minOf(len / 3f, 0.8f * rate)
        for (h in harm.indices) {
            val w = 2.0 * Math.PI * f * (h + 1) / rate
            val hg = harm[h] * amp
            var i = start
            while (i < end) {
                val t = (i - start).toFloat()
                var env = if (t < att) t / att else 1f
                if (decay > 0f) env *= Math.exp(-decay * t / rate.toDouble()).toFloat()
                val left = (start + len - i).toFloat()
                if (left < rel) env *= left / rel
                val v = if (vib == 0f) Math.sin(w * t).toFloat()
                else Math.sin(w * t + vib * Math.sin(2.0 * Math.PI * 5.0 * t / rate)).toFloat()
                out[i] += v * env * hg
                i++
            }
        }
    }

    private fun padMusic(n: Int, rate: Int, root: Double, chords: List<IntArray>, secPer: Float, attack: Float, amp: Float, shimmer: Boolean, r: Random): FloatArray {
        val out = FloatArray(n)
        val len = (secPer * rate).toInt()
        val harm = floatArrayOf(1f, 0.4f, 0.15f)
        var pos = 0
        var k = 0
        while (pos < n) {
            val ch = chords[k % chords.size]
            for (s in ch) {
                tone(out, pos, len + (rate / 2), hz(root, s), amp, rate, attack, 0f, 0.0f, harm)
            }
            tone(out, pos, len + (rate / 2), hz(root, ch[0] - 12), amp * 0.7f, rate, attack, 0f, 0.0f, floatArrayOf(1f, 0.2f))
            if (shimmer) {
                for (j in 0 until 3) {
                    val st = pos + r.nextInt(maxOf(1, len))
                    val s = ch[r.nextInt(ch.size)] + 24
                    tone(out, st, rate * 2, hz(root, s), amp * 0.35f, rate, 0.02f, 1.6f, 0.0f, floatArrayOf(1f, 0.3f))
                }
            }
            pos += len
            k++
        }
        return out
    }

    private fun pluckMusic(n: Int, rate: Int, root: Double, scale: IntArray, stepSec: Float, decay: Float, amp: Float, arpeggio: Boolean, r: Random): FloatArray {
        val out = FloatArray(n)
        val step = (stepSec * rate).toInt()
        var pos = 0
        var idx = 0
        val harm = floatArrayOf(1f, 0.35f, 0.12f)
        while (pos < n) {
            if (arpeggio) idx = (idx + 1) % scale.size
            else idx = (idx + r.nextInt(3) - 1 + scale.size) % scale.size
            val oct = if (r.nextInt(5) == 0) 12 else 0
            tone(out, pos, (step * 4), hz(root, scale[idx] + oct), amp, rate, 0.005f, decay, 0.0f, harm)
            pos += step
        }
        return out
    }

    private fun neyMusic(n: Int, rate: Int, r: Random): FloatArray {
        val root = 146.83 // ري
        val out = FloatArray(n)
        val dr = floatArrayOf(1f, 0.25f)
        tone(out, 0, n, root, 0.18f, rate, 2f, 0f, 0f, dr)
        tone(out, 0, n, hz(root, 7), 0.12f, rate, 2f, 0f, 0f, dr)
        val scale = intArrayOf(12, 13, 16, 17, 19, 20, 23, 24)
        var pos = (rate * 1.5f).toInt()
        var cur = 3
        val harm = floatArrayOf(1f, 0.3f, 0.1f)
        while (pos < n) {
            cur = (cur + r.nextInt(3) - 1).coerceIn(0, scale.size - 1)
            val len = (rate * (1.8f + r.nextFloat() * 2.2f)).toInt()
            tone(out, pos, len, hz(root, scale[cur]), 0.22f, rate, 0.25f, 0f, 0.9f, harm)
            pos += len + (rate * (0.3f + r.nextFloat() * 0.8f)).toInt()
        }
        return out
    }

    private fun musicSegment(id: String, n: Int, rate: Int): FloatArray {
        val r = Random(id.hashCode().toLong() + 7L)
        val c = 130.81 // دو
        val out: FloatArray = when (id) {
            "dua_sakina" -> padMusic(n, rate, c, listOf(intArrayOf(0, 4, 7), intArrayOf(7, 11, 14), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12)), 6f, 1.6f, 0.16f, true, r)
            "dua_khushu" -> padMusic(n, rate, c / 2, listOf(intArrayOf(0, 3, 7), intArrayOf(-2, 2, 5), intArrayOf(-4, 0, 3), intArrayOf(-2, 2, 5)), 8f, 2.5f, 0.2f, false, r)
            "dua_noor" -> padMusic(n, rate, c, listOf(intArrayOf(0, 4, 7), intArrayOf(5, 9, 12), intArrayOf(7, 11, 14), intArrayOf(0, 4, 7)), 5f, 1.2f, 0.14f, true, r)
            "dua_ney" -> neyMusic(n, rate, r)
            "dua_rahma" -> padMusic(n, rate, c * 0.84, listOf(intArrayOf(0, 4, 7), intArrayOf(2, 5, 9), intArrayOf(4, 7, 11), intArrayOf(5, 9, 12)), 7f, 2.0f, 0.15f, true, r)
            "dua_night" -> padMusic(n, rate, c / 2, listOf(intArrayOf(0, 7, 12), intArrayOf(0, 3, 7), intArrayOf(-4, 3, 8), intArrayOf(0, 7, 12)), 9f, 3f, 0.18f, true, r)
            "calm_piano" -> pluckMusic(n, rate, c, intArrayOf(0, 4, 7, 12, 7, 4, 9, 5), 0.6f, 1.6f, 0.2f, true, r)
            "lullaby" -> pluckMusic(n, rate, c * 2, intArrayOf(0, 2, 4, 7, 9, 12), 0.75f, 2.4f, 0.16f, false, r)
            "happy" -> pluckMusic(n, rate, c * 2, intArrayOf(0, 2, 4, 5, 7, 9, 11, 12), 0.28f, 3.2f, 0.17f, false, r)
            "cinematic" -> padMusic(n, rate, c / 2, listOf(intArrayOf(0, 7, 12), intArrayOf(-4, 3, 8), intArrayOf(-2, 5, 10), intArrayOf(0, 7, 12)), 7f, 3.5f, 0.22f, false, r)
            "suspense" -> padMusic(n, rate, c / 2, listOf(intArrayOf(0, 1, 7), intArrayOf(0, 1, 6), intArrayOf(-1, 0, 6), intArrayOf(0, 1, 7)), 6f, 2.5f, 0.2f, false, r)
            else -> FloatArray(n)
        }
        normalize(out, 0.9f)
        return out
    }

    // يكرر مقطعًا قصيرًا حتى الطول المطلوب مع تداخل ناعم بين التكرارات
    private fun loopFill(seg: FloatArray, total: Int, fade: Int): FloatArray {
        if (seg.size >= total) return seg.copyOf(total)
        val out = FloatArray(total)
        val len = seg.size
        val f = minOf(fade, len / 3)
        val step = len - f
        var pos = 0
        var first = true
        while (pos < total) {
            for (j in 0 until len) {
                val idx = pos + j
                if (idx >= total) break
                var w = 1f
                if (!first && j < f) w *= j.toFloat() / f.toFloat()
                if (j >= len - f) w *= (len - 1 - j).toFloat() / f.toFloat()
                out[idx] += seg[j] * w
            }
            pos += step
            first = false
        }
        return out
    }

    private fun layer(kind: String, id: String, total: Int, rate: Int): FloatArray {
        val segN = minOf(total, rate * 26)
        val seg = if (kind == "music") musicSegment(id, segN, rate) else ambSegment(id, segN, rate)
        return loopFill(seg, total, rate * 2)
    }

    private fun toBytes(x: FloatArray): ByteArray {
        var p = 0.0001f
        for (v in x) {
            val a = Math.abs(v)
            if (a > p) p = a
        }
        val g = if (p > 0.97f) 0.97f / p else 1f
        val out = ByteArray(x.size * 2)
        for (i in x.indices) {
            val s = (x[i] * g * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun fadeEnds(x: FloatArray, rate: Int, inSec: Float, outSec: Float) {
        val a = (inSec * rate).toInt()
        val b = (outSec * rate).toInt()
        for (i in 0 until minOf(a, x.size)) x[i] *= i.toFloat() / a.toFloat()
        for (i in 0 until minOf(b, x.size)) x[x.size - 1 - i] *= i.toFloat() / b.toFloat()
    }

    // عينة 12 ثانية من موسيقى أو مؤثر (kind: music | amb) بصيغة PCM 16 بت
    fun preview(kind: String, id: String, rate: Int): ByteArray {
        val total = rate * 12
        val x = layer(kind, id, total, rate)
        fadeEnds(x, rate, 0.8f, 1.5f)
        return toBytes(x)
    }

    // مزج الكلام مع الموسيقى والمؤثرات. الأحجام من 0 إلى 100
    fun mix(speech: ByteArray, rate: Int, musicId: String, amb1: String, amb2: String, mv: Int, a1v: Int, a2v: Int, introSec: Int, outroSec: Int): ByteArray {
        val ns = speech.size / 2
        val intro = introSec * rate
        val outro = outroSec * rate
        val total = intro + ns + outro
        val out = FloatArray(total)
        for (i in 0 until ns) {
            val lo = speech[i * 2].toInt() and 0xFF
            val hi = speech[i * 2 + 1].toInt()
            out[intro + i] = ((hi shl 8) or lo) / 32768f
        }
        // غلاف خفض الموسيقى أثناء الكلام
        val duck = FloatArray(total)
        var s = 1f
        for (i in 0 until total) {
            val target = if (i >= intro && i < intro + ns) 0.5f else 1f
            s += 0.0004f * (target - s)
            duck[i] = s
        }
        if (musicId.isNotEmpty() && mv > 0) {
            val m = layer("music", musicId, total, rate)
            fadeEnds(m, rate, 1.5f, 2.5f)
            val g = mv / 100f
            for (i in 0 until total) out[i] += m[i] * g * duck[i]
        }
        if (amb1.isNotEmpty() && a1v > 0) {
            val a = layer("amb", amb1, total, rate)
            fadeEnds(a, rate, 1.5f, 2.5f)
            val g = a1v / 100f
            for (i in 0 until total) out[i] += a[i] * g * (0.5f + 0.5f * duck[i])
        }
        if (amb2.isNotEmpty() && a2v > 0) {
            val a = layer("amb", amb2, total, rate)
            fadeEnds(a, rate, 1.5f, 2.5f)
            val g = a2v / 100f
            for (i in 0 until total) out[i] += a[i] * g * (0.5f + 0.5f * duck[i])
        }
        return toBytes(out)
    }
}
