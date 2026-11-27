package com.mostafa.smartvision

import org.json.JSONArray
import java.net.URLEncoder

// ترجمة النصوص الأجنبية للعربية (لأخبار وصحف ومقالات اللغات الأجنبية).
// بتستخدم خدمة ترجمة جوجل المجانية، من غير مفتاح. لو فشلت نرجع النص الأصلي.
object NewsTranslate {
    private const val URL = "https://translate.googleapis.com/translate_a/single"

    // يترجم نصًا واحدًا (حتى نحو 4000 حرف). يرجع null عند الفشل
    fun one(text: String): String? {
        if (text.isBlank()) return ""
        val q = URLEncoder.encode(text, "UTF-8")
        var r = Http.post(
            URL, "client=gtx&sl=auto&tl=ar&dt=t&q=" + q,
            mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"), 20000
        )
        if (r.code != 200) {
            // احتياطي: GET للنصوص القصيرة
            val short = URLEncoder.encode(text.take(1200), "UTF-8")
            r = Http.get(URL + "?client=gtx&sl=auto&tl=ar&dt=t&q=" + short, emptyMap(), 20000)
            if (r.code != 200) return null
        }
        return try {
            val segs = JSONArray(r.body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val s = segs.optJSONArray(i) ?: continue
                sb.append(s.optString(0, ""))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            null
        }
    }

    // يترجم عناوين وملخصات الأخبار الأجنبية. كل عنوان في سطر، فنقسم الرد على الأسطر
    fun items(items: List<NewsItem>, cancel: () -> Boolean): List<NewsItem> {
        val titles = translateLines(items.map { it.title }, cancel)
        val sums = translateLines(items.map { it.summary.replace("\n", " ").take(300) }, cancel)
        val out = ArrayList<NewsItem>()
        for (i in items.indices) {
            val n = items[i]
            val t = titles[i]
            val s = sums[i]
            val ni = NewsItem(
                if (t.isNotEmpty()) t else n.title,
                n.source, n.link, n.date,
                if (n.summary.isEmpty()) "" else (if (s.isNotEmpty()) s else n.summary)
            )
            ni.foreign = true
            ni.origTitle = n.title
            out.add(ni)
        }
        return out
    }

    // يرجع نفس عدد الأسطر؛ السطر الفاضي معناه "ما اتترجمش"
    private fun translateLines(lines: List<String>, cancel: () -> Boolean): List<String> {
        val res = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (cancel()) {
                while (res.size < lines.size) res.add("")
                return res
            }
            // دفعة حتى 15 سطرًا وبحد أقصى حروف
            val batch = ArrayList<String>()
            var chars = 0
            while (i < lines.size && batch.size < 15 && chars < 3000) {
                val l = lines[i].replace("\n", " ").trim()
                batch.add(l)
                chars += l.length + 1
                i++
            }
            if (batch.all { it.isEmpty() }) {
                for (b in batch) res.add("")
                continue
            }
            val tr = one(batch.joinToString("\n"))
            val parts = if (tr == null) emptyList() else tr.split("\n").map { it.trim() }
            if (parts.size == batch.size) {
                res.addAll(parts)
            } else {
                // اختلف عدد الأسطر: نترجم كل سطر لوحده
                for (b in batch) {
                    if (cancel()) {
                        res.add("")
                    } else if (b.isEmpty()) {
                        res.add("")
                    } else {
                        res.add(one(b) ?: "")
                    }
                }
            }
        }
        return res
    }

    // يترجم نصًا طويلًا (نص خبر كامل) على أجزاء. يرجع null لو فشل كل شيء
    fun long(text: String, cancel: () -> Boolean): String? {
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        for (para in text.split("\n")) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (cur.length + p.length > 1500 && cur.isNotEmpty()) {
                chunks.add(cur.toString())
                cur = StringBuilder()
            }
            if (p.length > 1500) {
                var s = p
                while (s.length > 1500) {
                    var cut = s.lastIndexOf(". ", 1500)
                    if (cut < 300) cut = 1500 else cut += 1
                    chunks.add(s.substring(0, cut))
                    s = s.substring(cut).trim()
                }
                if (s.isNotEmpty()) cur.append(s).append("\n")
            } else {
                cur.append(p).append("\n")
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        val out = ArrayList<String>()
        var ok = 0
        for (c in chunks) {
            if (cancel()) return null
            val t = one(c.trim())
            if (t != null && t.isNotEmpty()) {
                out.add(t)
                ok++
            } else {
                out.add(c.trim())
            }
        }
        if (ok == 0) return null
        return out.joinToString("\n\n")
    }
}
