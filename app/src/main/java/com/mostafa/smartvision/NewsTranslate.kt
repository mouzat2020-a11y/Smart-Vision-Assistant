package com.mostafa.smartvision

import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

// ترجمة النصوص الأجنبية للعربية بخدمة ترجمة جوجل المجانية (من غير مفتاح).
// السرعة: خمس طلبات في نفس الوقت، وكل طلب يُعاد مرة لو فشل، ولو فشل نرجع النص الأصلي.
object NewsTranslate {
    private const val URL = "https://translate.googleapis.com/translate_a/single"

    // ترجمة نص واحد (حتى 1800 حرف). null عند الفشل
    private fun gtx(text: String): String? {
        val q = URLEncoder.encode(text.take(1800), "UTF-8")
        val r = Http.get(URL + "?client=gtx&sl=auto&tl=ar&dt=t&q=" + q, emptyMap(), 20000)
        if (r.code != 200) return null
        return try {
            val segs = JSONArray(r.body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val s = segs.optJSONArray(i) ?: continue
                sb.append(s.optString(0, ""))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }

    // يترجم قائمة نصوص بالتوازي. يرجع النتيجة بنفس الترتيب، وعدد اللي اتترجم فعلًا
    private fun manyCount(list: List<String>, ownerTag: String, cancel: () -> Boolean): Pair<List<String>, Int> {
        val n = list.size
        val res = arrayOfNulls<String>(n)
        val next = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val workers = ArrayList<Thread>()
        val count = if (n < 5) n else 5
        for (w in 0 until count) {
            workers.add(Http.thread(ownerTag) {
                while (true) {
                    if (cancel()) break
                    val idx = next.getAndIncrement()
                    if (idx >= n) break
                    val s = list[idx]
                    if (s.isBlank()) {
                        res[idx] = s
                        continue
                    }
                    var t = gtx(s)
                    if (t == null && !cancel()) t = gtx(s)
                    if (t != null) ok.incrementAndGet()
                    res[idx] = t ?: s
                }
            })
        }
        for (th in workers) {
            try { th.join() } catch (e: Exception) { }
        }
        val out = ArrayList<String>()
        for (i in 0 until n) out.add(res[i] ?: list[i])
        return Pair(out, ok.get())
    }

    fun many(list: List<String>, ownerTag: String, cancel: () -> Boolean): List<String> {
        if (list.isEmpty()) return emptyList()
        return manyCount(list, ownerTag, cancel).first
    }

    // يترجم عناوين الأخبار الأجنبية ويرجّع أخبارًا جديدة بعناوين عربية (والعنوان الأصلي محفوظ)
    fun titles(items: List<NewsItem>, ownerTag: String, cancel: () -> Boolean): List<NewsItem> {
        val titles = items.map { it.title.replace("\n", " ").trim() }
        // الأسرع: كل العناوين في طلب واحد (كل عنوان في سطر)، ولو عدد الأسطر اختلف نرجع للترجمة المتوازية
        var tr: List<String>? = null
        val joined = titles.joinToString("\n")
        if (joined.length <= 1700 && !cancel()) {
            val r = gtx(joined)
            if (r != null) {
                val parts = r.split("\n").map { it.trim() }
                if (parts.size == titles.size && parts.none { it.isEmpty() }) tr = parts
            }
        }
        if (tr == null) tr = many(titles, ownerTag, cancel)
        val out = ArrayList<NewsItem>()
        for (i in items.indices) {
            val n = items[i]
            val t = tr[i]
            val ni = NewsItem(if (t.isNotEmpty()) t else n.title, n.source, n.link, n.date, n.summary)
            ni.foreign = true
            ni.origTitle = n.title
            out.add(ni)
        }
        return out
    }

    // يقسم النص إلى أجزاء عند نهايات الجمل، كل جزء لا يزيد عن n حرف
    fun splitText(text: String, n: Int): List<String> {
        val t = text.replace(Regex("([.!?])\\s+"), "$1\n").replace(Regex("(\u061F)\\s+"), "$1\n")
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        for (line in t.split("\n")) {
            val sent = line.trim()
            if (sent.isEmpty()) continue
            if (sent.length > n) {
                if (cur.isNotEmpty()) {
                    chunks.add(cur.toString())
                    cur = StringBuilder()
                }
                var pos = 0
                while (pos < sent.length) {
                    var e = if (pos + n < sent.length) pos + n else sent.length
                    if (e < sent.length) {
                        val sp = sent.lastIndexOf(' ', e)
                        if (sp > pos + n / 2) e = sp
                    }
                    val piece = sent.substring(pos, e).trim()
                    if (piece.isNotEmpty()) chunks.add(piece)
                    pos = e
                }
            } else if (cur.length + sent.length + 1 > n) {
                if (cur.isNotEmpty()) chunks.add(cur.toString())
                cur = StringBuilder(sent)
            } else {
                if (cur.isNotEmpty()) cur.append(" ")
                cur.append(sent)
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    // يترجم نصًا طويلًا (نص خبر) بأجزاء متوازية. null لو ما اتترجمش ولا جزء
    fun long(text: String, ownerTag: String, cancel: () -> Boolean): String? {
        val chunks = splitText(text, 1400)
        if (chunks.isEmpty()) return null
        val r = manyCount(chunks, ownerTag, cancel)
        if (r.second == 0) return null
        return r.first.joinToString(" ")
    }
}
