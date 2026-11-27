package com.mostafa.smartvision

import android.text.Html
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder

// جلب نص الخبر الكامل من صفحة الجريدة، وفك روابط جوجل نيوز المشفّرة.
// الطرق مرتبة: الصفحة مباشرة، ثم القارئ الاحتياطي (r.jina.ai) لو الموقع منع الطلب.
object NewsArticle {
    class Body(val text: String?, val reason: String)

    private const val MAX_TEXT = 12000
    private val JUNK = listOf(
        "اقرأ أيضا", "اقرأ أيضًا", "اقرأ ايضا", "اشترك في", "جميع الحقوق", "تابعونا على",
        "حقوق النشر", "Read more", "Subscribe", "All rights reserved"
    )
    private val FLAGS = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    fun load(item: NewsItem, ownerTag: String, cancel: () -> Boolean): Body {
        if (item.full.length > 200) return Body(item.full, "")
        var url = item.link
        var reason = ""
        var lead = ""
        var jinaOnly = false
        if (url.contains("news.google.com")) {
            val real = resolveGNews(url, cancel)
            if (cancel()) return Body(null, "تم الإيقاف")
            if (real != null) url = real else jinaOnly = true
        }
        if (!jinaOnly) {
            val r = Http.get(url, mapOf("Accept" to "text/html,application/xhtml+xml"), 25000)
            if (cancel() || r.code == -2) return Body(null, "تم الإيقاف")
            if (r.code == 200) {
                val t = extract(r.body)
                if (t.length > 250) return Body(cut(t), "")
                reason = "الصفحة اتفتحت بس ما لقيتش فيها نص الخبر"
                lead = metaDesc(r.body)
            } else if (r.code == 403 || r.code == 401 || r.code == 429) {
                reason = "الموقع منع قراءة الخبر مباشرة (رد " + r.code + ")"
            } else if (r.code == -1 && r.body == "offline") {
                return Body(null, "مفيش اتصال بالإنترنت")
            } else {
                reason = "الموقع رد بـ " + r.code
            }
        } else {
            reason = "تعذر فك رابط جوجل نيوز"
        }
        // القارئ الاحتياطي: يرجّع الصفحة كنص نظيف حتى لو الموقع بيمنع الطلبات العادية
        val j = Http.get("https://r.jina.ai/" + url, mapOf("Accept" to "text/plain"), 35000)
        if (cancel() || j.code == -2) return Body(null, "تم الإيقاف")
        if (j.code == 200) {
            val t = jinaText(j.body)
            if (t.length > 300) return Body(cut(t), "")
            reason = reason + " | والقارئ الاحتياطي ما لقاش نص كفاية"
        } else {
            reason = reason + " | القارئ الاحتياطي رد: " + j.code
        }
        if (lead.isNotEmpty()) return Body(lead, "")
        return Body(null, "تعذر تحميل نص الخبر ($reason). افتح الخبر في المتصفح")
    }

    private fun cut(t: String): String {
        return if (t.length > MAX_TEXT) t.substring(0, MAX_TEXT).trim() + "..." else t
    }

    private fun clean(s: String): String {
        val t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun isJunk(c: String): Boolean {
        for (j in JUNK) if (c.contains(j, true)) return true
        return false
    }

    private fun endp(c: String): String {
        if (c.endsWith(".") || c.endsWith("!") || c.endsWith("?") || c.endsWith(":") || c.endsWith("\u061F")) return c
        return "$c."
    }

    // نص من JSON: pos أول حرف بعد علامة الاقتباس اللي بتفتح النص
    private fun jsonStrAt(s: String, pos: Int): String? {
        var i = pos
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '"') break
            i++
        }
        if (i >= s.length) return null
        val raw = s.substring(pos, i)
        return try {
            JSONObject("{\"a\":\"" + raw + "\"}").getString("a")
        } catch (e: Exception) {
            null
        }
    }

    private fun paras(scope: String, minLen: Int): String {
        val parts = ArrayList<String>()
        for (m in Regex("<p[^>]*>(.*?)</p>", FLAGS).findAll(scope)) {
            val c = clean(m.groupValues[1])
            if (c.length > minLen && !isJunk(c)) parts.add(endp(c))
        }
        return parts.joinToString("\n\n")
    }

    fun extract(html0: String): String {
        var html = html0
        if (html.length > 900000) html = html.substring(0, 900000)
        // نحذف السكربتات (ونحتفظ ببيانات JSON-LD لأن فيها نص المقال أحيانًا) والأنماط
        html = Regex("<script[^>]*>.*?</script>", FLAGS).replace(html) { m ->
            if (m.value.contains("articleBody")) m.value else ""
        }
        html = Regex("<style[^>]*>.*?</style>", FLAGS).replace(html, "")
        // 1) نص المقال الجاهز داخل بيانات الصفحة
        val ab = Regex("\"articleBody\"\\s*:\\s*\"").find(html)
        if (ab != null) {
            val body = jsonStrAt(html, ab.range.last + 1)
            if (body != null) {
                val parts = ArrayList<String>()
                for (line in body.split("\n")) {
                    val c = clean(line)
                    if (c.length > 30) parts.add(endp(c))
                }
                val t = parts.joinToString("\n\n")
                if (t.length > 300) return t
            }
        }
        // 2) فقرات أكبر عنصر article، ثم الصفحة كلها
        var best = ""
        for (m in Regex("<article[^>]*>(.*?)</article>", FLAGS).findAll(html)) {
            if (m.groupValues[1].length > best.length) best = m.groupValues[1]
        }
        var t = if (best.isNotEmpty()) paras(best, 60) else ""
        if (t.length < 300) t = paras(html, 60)
        // 3) مواقع تضع الفقرات في div عادي بدل p
        if (t.length < 300) {
            val parts = ArrayList<String>()
            for (m in Regex("<div[^>]*>([^<]+)</div>", FLAGS).findAll(html)) {
                val c = clean(m.groupValues[1])
                if (c.length > 120 && !isJunk(c)) parts.add(endp(c))
            }
            val t2 = parts.joinToString("\n\n")
            if (t2.length > t.length) t = t2
        }
        if (t.length < 300) {
            val t3 = paras(html, 40)
            if (t3.length > t.length) t = t3
        }
        return t
    }

    // وصف الصفحة (أول فقرة) من وسوم meta، لو نص الخبر الكامل ما اتجابش
    private fun metaDesc(html: String): String {
        val patterns = listOf(
            "<meta[^>]*property=\"og:description\"[^>]*content=\"([^\"]*)\"",
            "<meta[^>]*content=\"([^\"]*)\"[^>]*property=\"og:description\"",
            "<meta[^>]*name=\"description\"[^>]*content=\"([^\"]*)\"",
            "<meta[^>]*content=\"([^\"]*)\"[^>]*name=\"description\""
        )
        for (p in patterns) {
            val m = Regex(p, FLAGS).find(html)
            if (m != null) {
                val d = clean(m.groupValues[1])
                if (d.length >= 60) return d
            }
        }
        return ""
    }

    private fun jinaText(raw: String): String {
        var body = raw
        val k = raw.indexOf("Markdown Content:")
        if (k >= 0) body = raw.substring(k + 17)
        body = Regex("!\\[[^\\]]*\\]\\([^)]*\\)").replace(body, "")
        body = Regex("\\[([^\\]]*)\\]\\([^)]*\\)").replace(body, "$1")
        val parts = ArrayList<String>()
        for (line in body.split("\n")) {
            val c = line.trim()
            if (c.length > 120 && !c.startsWith("#") && !c.startsWith("*") && !c.startsWith("-") &&
                !c.startsWith("|") && !c.startsWith(">") && !c.contains("http") && !isJunk(c)) {
                parts.add(endp(c))
            }
        }
        return parts.joinToString("\n\n")
    }

    // ---------- فك روابط جوجل نيوز ----------
    private fun resolveGNews(link: String, cancel: () -> Boolean): String? {
        val idm = Regex("/(?:articles|read)/([^/?#]+)").find(link) ?: return null
        val id = idm.groupValues[1]
        // الروابط القديمة فيها الرابط الحقيقي داخل الكود نفسه
        try {
            val bytes = Base64.decode(id, Base64.URL_SAFE)
            val s = String(bytes, Charsets.ISO_8859_1)
            val m = Regex("https?://[A-Za-z0-9\\-._~:/?#@!\$&'()*+,;=%]+").find(s)
            if (m != null && !m.value.contains("google.com")) return m.value
        } catch (e: Exception) { }
        for (attempt in 0..1) {
            if (cancel()) return null
            val params = gnParams(id)
            if (params != null) {
                val u = gnBatch(id, params.first, params.second)
                if (u != null) return u
            }
            if (attempt == 0) {
                var waited = 0
                while (waited < 1500 && !cancel()) {
                    Thread.sleep(250)
                    waited += 250
                }
            }
        }
        return null
    }

    private fun gnParams(id: String): Pair<String, String>? {
        val urls = listOf(
            "https://news.google.com/articles/" + id + "?hl=ar&gl=EG&ceid=EG%3Aar",
            "https://news.google.com/rss/articles/" + id + "?hl=ar&gl=EG&ceid=EG:ar"
        )
        for (u in urls) {
            val r = Http.get(u, emptyMap(), 15000)
            if (r.code == 200) {
                val sg = Regex("data-n-a-sg=\"([^\"]+)\"").find(r.body)
                val ts = Regex("data-n-a-ts=\"([^\"]+)\"").find(r.body)
                if (sg != null && ts != null) return Pair(sg.groupValues[1], ts.groupValues[1])
            }
        }
        return null
    }

    private fun gnBatch(id: String, sg: String, ts: String): String? {
        val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"" +
            id + "\"," + ts + ",\"" + sg + "\"]"
        val req = "[[[\"Fbv4je\"," + JSONObject.quote(inner) + "]]]"
        val body = "f.req=" + URLEncoder.encode(req, "UTF-8")
        val r = Http.post(
            "https://news.google.com/_/DotsSplashUi/data/batchexecute", body,
            mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"), 20000
        )
        if (r.code != 200) return null
        var u: String? = null
        val m1 = Regex("garturlres\\\\\",\\\\\"(.*?)\\\\\"").find(r.body)
        if (m1 != null) u = m1.groupValues[1]
        if (u == null) {
            val m2 = Regex("garturlres.*?(https?:[^\"]+)", FLAGS).find(r.body)
            if (m2 != null) u = m2.groupValues[1]
        }
        if (u == null) return null
        u = unescapeUrl(u)
        if (u.startsWith("http") && !u.contains("google.com")) return u
        return null
    }

    private fun unescapeUrl(u0: String): String {
        var u = u0
        u = Regex("\\\\+u([0-9a-fA-F]{4})").replace(u) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        u = u.replace("\\\\/", "/").replace("\\/", "/")
        u = u.trimEnd('\\')
        return u
    }
}
