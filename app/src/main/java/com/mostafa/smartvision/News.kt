package com.mostafa.smartvision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Html
import android.util.Xml
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// خبر واحد
class NewsItem(val title: String, val source: String, val link: String, val date: Long, var summary: String) {
    // نص الخبر الأصلي (بلغة الجريدة) بعد تحميله، ثم النص النهائي للقراءة (مترجم لو الخبر أجنبي)
    var fullOrig: String = ""
    var full: String = ""
    var tried: Boolean = false
    // للأخبار الأجنبية بعد ترجمة عنوانها للعربية
    var foreign: Boolean = false
    var origTitle: String = ""
    var sumTr: Boolean = false
    // لو التحميل شغال دلوقتي (تحميل مسبق مثلًا)، فبدل ما نكرر الطلب بنستناه
    @Volatile var loading: Boolean = false
}

class NewsResult(val items: List<NewsItem>, val error: String?)

object NewsData {
    val ORDER = listOf("EG", "SA", "AE", "DZ", "MA", "TN", "LY", "JO", "LB", "SY", "IQ", "KW", "QA", "BH", "OM", "YE", "PS", "SD", "MR")
    val NAMES = mapOf(
        "EG" to "مصر", "SA" to "السعودية", "AE" to "الإمارات", "DZ" to "الجزائر", "MA" to "المغرب",
        "TN" to "تونس", "LY" to "ليبيا", "JO" to "الأردن", "LB" to "لبنان", "SY" to "سوريا",
        "IQ" to "العراق", "KW" to "الكويت", "QA" to "قطر", "BH" to "البحرين", "OM" to "سلطنة عمان",
        "YE" to "اليمن", "PS" to "فلسطين", "SD" to "السودان", "MR" to "موريتانيا"
    )

    private const val MAX_AGE_MS = 30L * 24L * 3600L * 1000L

    // ---------- الإعدادات ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("news", Context.MODE_PRIVATE)

    fun isAuto(ctx: Context): Boolean {
        val c = prefs(ctx).getString("country", "") ?: ""
        return !NAMES.containsKey(c)
    }

    fun setAuto(ctx: Context) {
        prefs(ctx).edit().putString("country", "").apply()
    }

    fun setManual(ctx: Context, cc: String) {
        prefs(ctx).edit().putString("country", cc).apply()
    }

    // ترجمة المصادر الأجنبية للعربية: شغالة افتراضيًا
    fun translateOn(ctx: Context): Boolean = prefs(ctx).getBoolean("translate", true)

    fun setTranslate(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("translate", on).apply()
    }

    // دولة الجهاز: من الشبكة أو الشريحة، ثم لغة/منطقة الجهاز
    fun deviceCountry(ctx: Context): String? {
        var cc = ""
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            cc = tm.networkCountryIso ?: ""
            if (cc.length != 2) cc = tm.simCountryIso ?: ""
        } catch (e: Exception) {
            cc = ""
        }
        cc = cc.uppercase()
        if (NAMES.containsKey(cc)) return cc
        val l = Locale.getDefault().country.uppercase()
        if (NAMES.containsKey(l)) return l
        return null
    }

    fun activeCountry(ctx: Context): String {
        val c = prefs(ctx).getString("country", "") ?: ""
        if (NAMES.containsKey(c)) return c
        return deviceCountry(ctx) ?: "EG"
    }

    // ---------- التحميل ----------
    fun loadUrls(primary: List<String>, fallback: List<String>, limit: Int, ownerTag: String, cancel: () -> Boolean): NewsResult {
        var items = fetchAll(primary, ownerTag, limit)
        if (items.isEmpty() && !cancel() && fallback.isNotEmpty()) items = fetchAll(fallback, ownerTag, limit)
        if (cancel()) return NewsResult(emptyList(), "تم الإيقاف")
        if (items.isEmpty()) {
            val msg = if (!Net.isOnline()) "مفيش اتصال بالإنترنت" else "ما لقيتش أخبارًا في القسم ده دلوقتي. جرّب تحديثًا بعد شوية"
            return NewsResult(emptyList(), msg)
        }
        return NewsResult(items, null)
    }

    // يجلب عدة خلاصات في نفس الوقت، ثم يدمجها ويرتبها من الأحدث
    private fun fetchAll(urls: List<String>, ownerTag: String, limit: Int): List<NewsItem> {
        val lists = arrayOfNulls<List<NewsItem>>(urls.size)
        val threads = ArrayList<Thread>()
        for (i in urls.indices) {
            val u = urls[i]
            threads.add(Http.thread(ownerTag) {
                lists[i] = fetchOne(u)
            })
        }
        for (t in threads) {
            try { t.join() } catch (e: Exception) { }
        }
        val merged = ArrayList<NewsItem>()
        val seen = HashSet<String>()
        val now = System.currentTimeMillis()
        for (l in lists) {
            if (l == null) continue
            for (n in l) {
                if (n.date > 0 && now - n.date > MAX_AGE_MS) continue
                val key = n.title.replace(" ", "")
                if (seen.add(key)) merged.add(n)
            }
        }
        merged.sortByDescending { it.date }
        return if (merged.size > limit) ArrayList(merged.subList(0, limit)) else merged
    }

    private fun fetchOne(url: String): List<NewsItem> {
        try {
            val r = Http.get(url, mapOf("Accept" to "application/rss+xml, application/xml, text/xml, */*"), 20000)
            if (r.code != 200) return emptyList()
            return parse(r.body)
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // ---------- قراءة RSS ----------
    private fun plain(s: String): String {
        val t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        val formats = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (f in formats) {
            try {
                val df = SimpleDateFormat(f, Locale.US)
                df.timeZone = TimeZone.getTimeZone("UTC")
                val d = df.parse(t)
                if (d != null) return d.time
            } catch (e: Exception) { }
        }
        return 0L
    }

    fun parse(xml: String): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var ev = p.eventType
            var inItem = false
            var tag = ""
            var channelTitle = ""
            var title = ""
            var link = ""
            var dateStr = ""
            var desc = ""
            var src = ""
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.name ?: ""
                    if (tag == "item" || tag == "entry") {
                        inItem = true
                        title = ""
                        link = ""
                        dateStr = ""
                        desc = ""
                        src = ""
                    } else if (inItem && tag == "link") {
                        val h = p.getAttributeValue(null, "href")
                        if (h != null && h.isNotEmpty()) link = h
                    }
                } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                    val t = p.text ?: ""
                    if (!inItem) {
                        if (tag == "title" && channelTitle.isEmpty()) channelTitle = t.trim()
                    } else {
                        when (tag) {
                            "title" -> title += t
                            "link" -> link += t
                            "pubDate", "published", "updated" -> dateStr += t
                            "description", "summary" -> desc += t
                            "source" -> src += t
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    val nm = p.name ?: ""
                    if (nm == "item" || nm == "entry") {
                        inItem = false
                        val n = buildItem(title, link, dateStr, desc, src, channelTitle)
                        if (n != null) out.add(n)
                    }
                    tag = ""
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // خلاصة تالفة: نرجع ما قرأناه حتى الآن
        }
        return out
    }

    private fun buildItem(titleRaw: String, linkRaw: String, dateStr: String, descRaw: String, srcRaw: String, channelTitle: String): NewsItem? {
        var title = plain(titleRaw)
        val link = linkRaw.trim()
        if (title.isEmpty() || !link.startsWith("http")) return null
        var source = plain(srcRaw)
        // عناوين جوجل نيوز بصيغة: العنوان - المصدر
        val cut = title.lastIndexOf(" - ")
        if (cut > 10) {
            val tail = title.substring(cut + 3).trim()
            if (source.isEmpty() || tail == source) {
                if (source.isEmpty()) source = tail
                title = title.substring(0, cut).trim()
            }
        }
        if (source.isEmpty()) source = plain(channelTitle)
        var summary = plain(descRaw)
        val normTitle = title.replace(" ", "")
        val normSum = summary.replace(" ", "")
        // وصف جوجل نيوز يكرر العنوان والمصدر فقط: لا فائدة منه
        if (summary.length < 25 || normSum.startsWith(normTitle.take(30)) || normTitle.startsWith(normSum.take(30))) summary = ""
        if (summary.length > 900) summary = summary.substring(0, 900).trim() + "..."
        return NewsItem(title, source, link, parseDate(dateStr), summary)
    }

    fun ago(date: Long): String {
        if (date <= 0L) return ""
        val mins = (System.currentTimeMillis() - date) / 60000L
        if (mins < 1) return "الآن"
        if (mins < 60) return "منذ " + mins + " دقيقة"
        val hours = mins / 60
        if (hours < 24) return "منذ " + hours + " ساعة"
        return "منذ " + (hours / 24) + " يوم"
    }
}

// تخزين مؤقت للعناوين: لو فتحت نفس التصنيف تاني خلال 10 دقايق بيفتح فورًا (زر "تحديث" بيجيب الجديد)
private class NewsCacheEntry(val t: Long, val items: List<NewsItem>)
private val NEWS_CACHE = HashMap<String, NewsCacheEntry>()

class NewsActivity : Activity() {
    // علامة هذه الشاشة: إلغاء طلباتها لا يقطع طلبات شاشة أخرى
    private val ownerTag = "news" + System.identityHashCode(this)
    // التحميل المسبق له علامته الخاصة، فيكمل شغله حتى لو فتحت تفاصيل خبر
    private val prefTag = ownerTag + "p"
    private var prefGen = 0
    private var prefList: List<NewsItem>? = null
    private var token = 0
    // لو فتحنا الشاشة من تبويب الأخبار مباشرة على قسم معين: الرجوع يخرج منها بدل الرئيسية الداخلية
    private var direct = false
    // 0 الرئيسية، 1 التصنيفات، 2 العناوين، 3 تفاصيل الخبر، 4 الإعدادات، 5 المساعدة
    private var screen = 0
    private var cc = "EG"
    private var secs: List<NewsSec> = emptyList()
    private var si = 0
    private var ci = 0
    private var items: List<NewsItem> = emptyList()
    private var idx = 0
    private var status: TextView? = null
    private val rates = floatArrayOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.7f, 2.0f, 2.5f)
    private val rateNames = listOf(
        "بطيء جدًا (0.7)", "بطيء (0.85)", "عادي (1.0)", "أسرع شوية (1.15)", "أسرع (1.3)",
        "سريع (1.5)", "سريع جدًا (1.7)", "سريع قوي (2.0)", "أقصى سرعة (2.5)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        applyCountry()
        val sc = intent.getStringExtra("screen") ?: ""
        val ex = intent.getIntExtra("section", -1)
        if (sc == "settings") {
            direct = true
            showSettings()
        } else if (sc == "help") {
            direct = true
            showHelp()
        } else if (ex >= 0 && ex < secs.size) {
            direct = true
            openSection(ex)
        } else {
            showMain()
        }
    }

    private fun backToTop() {
        if (direct) finish() else showMain()
    }

    private fun applyCountry() {
        cc = NewsData.activeCountry(this)
        secs = NewsSources.buildSections(cc)
    }

    private fun cancelWork() {
        token++
        Http.cancelOwner(ownerTag)
    }

    // يوقف التحميل المسبق (لما نسيب القايمة)
    private fun stopPrefetch() {
        prefGen++
        prefList = null
        Http.cancelOwner(prefTag)
    }

    private fun countryName(): String = NewsData.NAMES[cc] ?: ""

    // ---------- الشاشة الرئيسية: الأقسام التسعة ----------
    private fun showMain() {
        screen = 0
        stopPrefetch()
        cancelWork()
        Speaker.stop()
        val p = Page(this, "قارئ الأخبار")
        for (i in secs.indices) {
            p.button(secs[i].name) { openSection(i) }
        }
        p.button("الإعدادات") { showSettings() }
        p.button("مساعدة: شرح قارئ الأخبار") { showHelp() }
        p.bottomButton("رجوع للقائمة الرئيسية") { finish() }
    }

    // ---------- داخل القسم: التصنيفات أو المصادر ----------
    private fun openSection(i: Int) {
        si = i
        screen = 1
        stopPrefetch()
        cancelWork()
        Speaker.stop()
        val sec = secs[i]
        val p = Page(this, sec.name)
        var hasForeign = false
        for (c in sec.cats) if (c.foreign) hasForeign = true
        if (hasForeign && NewsData.translateOn(this)) {
            p.label("بعض المصادر بلغات أجنبية، وبتترجم للعربية تلقائيًا (الترجمة آلية وقد تكون غير دقيقة)")
        }
        for (k in sec.cats.indices) {
            p.button(sec.cats[k].name) { openHeadlines(k, false) }
        }
        p.bottomButton("رجوع للأقسام") { backToTop() }
    }

    // ---------- العناوين ----------
    private fun openHeadlines(k: Int, force: Boolean) {
        ci = k
        val sec = secs[si]
        val cat = sec.cats[k]
        val foreign = cat.foreign && NewsData.translateOn(this)
        val key = cc + ":" + si + ":" + k + ":" + (if (foreign) "t" else "n")
        val hit = NEWS_CACHE[key]
        if (hit != null && !force && System.currentTimeMillis() - hit.t < 600000L) {
            items = hit.items
            showHeadlines()
            return
        }
        screen = 2
        stopPrefetch()
        cancelWork()
        Speaker.stop()
        val my = token
        val p = Page(this, sec.name + " - " + cat.name)
        val st = p.label("جاري تحميل " + cat.name + "، انتظر قليلًا")
        Ui.live(st)
        p.bottomButton("رجوع") { openSection(si) }
        val limit = if (foreign) 12 else if (sec.kind == "news") 15 else 20
        Http.thread(ownerTag) {
            val res = NewsData.loadUrls(cat.urls, cat.fallback, limit, ownerTag) { my != token }
            var list = res.items
            if (foreign && list.isNotEmpty() && my == token) {
                runOnUiThread { if (my == token) st.text = "جاري ترجمة العناوين للعربية، انتظر قليلًا" }
                list = NewsTranslate.titles(list, ownerTag) { my != token }
            }
            runOnUiThread {
                if (my == token) {
                    if (list.isNotEmpty()) NEWS_CACHE[key] = NewsCacheEntry(System.currentTimeMillis(), list)
                    items = list
                    if (list.isEmpty()) showHeadlinesEmpty(res.error) else showHeadlines()
                }
            }
        }
    }

    private fun showHeadlinesEmpty(err: String?) {
        screen = 2
        val sec = secs[si]
        val p = Page(this, sec.name + " - " + sec.cats[ci].name)
        val st = p.label(err ?: "لا توجد أخبار دلوقتي")
        Ui.live(st)
        p.button("إعادة المحاولة") { openHeadlines(ci, true) }
        p.bottomButton("رجوع") { openSection(si) }
        p.root.announceForAccessibility(err ?: "لا توجد أخبار")
    }

    private fun showHeadlines() {
        screen = 2
        cancelWork()
        val sec = secs[si]
        val cat = sec.cats[ci]
        val p = Page(this, sec.name + " - " + cat.name)
        val st = p.label("عدد الأخبار: " + items.size)
        Ui.live(st)
        p.button("قراءة أول 10 عناوين") { readHeadlines() }
        p.button("إيقاف القراءة") { Speaker.stop() }
        p.button("تحديث") { openHeadlines(ci, true) }
        for (i in items.indices) {
            val n = items[i]
            val a = NewsData.ago(n.date)
            val label = n.title + " — " + n.source + (if (a.isNotEmpty()) " — " + a else "")
            p.button(label) { openItem(i) }
        }
        p.bottomButton("رجوع") { openSection(si) }
        p.root.announceForAccessibility("تم تحميل " + items.size + " خبرًا")
        if (prefList !== items) prefetch()
    }

    private fun readHeadlines() {
        Speaker.stop()
        val sb = StringBuilder()
        val n = if (items.size < 10) items.size else 10
        for (i in 0 until n) sb.append(items[i].title).append(".\n")
        Speaker.speak(sb.toString())
    }

    // تحميل مسبق في الخلفية لنص أول 4 أخبار (وترجمة أول خبرين لو أجنبية)، فيفتحوا بسرعة.
    // بيكمل شغله حتى لو فتحت تفاصيل خبر، ويقف بس لما تسيب القايمة
    private fun prefetch() {
        val list = items
        prefList = list
        val myGen = prefGen
        Http.thread(prefTag) {
            var k = 0
            while (k < list.size && k < 4) {
                if (myGen != prefGen) return@thread
                val n = list[k]
                if (n.full.isEmpty() && !n.tried && !n.loading) {
                    n.loading = true
                    try {
                        val b = NewsArticle.load(n, prefTag) { myGen != prefGen }
                        val t = b.text
                        if (t != null && myGen == prefGen) {
                            n.fullOrig = t
                            if (!n.foreign) {
                                n.full = t
                            } else if (k < 2) {
                                val tr = NewsTranslate.long(t, prefTag) { myGen != prefGen }
                                if (tr != null && myGen == prefGen) n.full = tr
                            }
                        }
                        if (t == null && myGen == prefGen) n.tried = true
                    } finally {
                        n.loading = false
                    }
                }
                k++
            }
        }
    }

    // ---------- تفاصيل الخبر ----------
    private fun openItem(i: Int, auto: Boolean = true) {
        if (i < 0 || i >= items.size) return
        idx = i
        screen = 3
        cancelWork()
        Speaker.stop()
        val n = items[i]
        val p = Page(this, "تفاصيل الخبر")
        p.text(n.title, 22f)
        val a = NewsData.ago(n.date)
        p.label(n.source + (if (a.isNotEmpty()) " — " + a else ""))
        if (n.foreign && n.origTitle.isNotEmpty()) p.label("العنوان الأصلي: " + n.origTitle)
        if (n.summary.isNotEmpty() && (!n.foreign || n.sumTr)) p.text(n.summary, 18f)
        val st = p.label("")
        Ui.live(st)
        status = st
        p.button("قراءة الخبر") { readNews(n, i) }
        p.button("قراءة العنوان فقط") {
            Speaker.stop()
            Speaker.speak(n.title)
        }
        p.button("إيقاف القراءة") { Speaker.stop() }
        p.button("زيادة سرعة القراءة") { adjustRate(1) }
        p.button("تقليل سرعة القراءة") { adjustRate(-1) }
        if (i < items.size - 1) p.button("الخبر التالي") { openItem(i + 1) }
        if (i > 0) p.button("الخبر السابق") { openItem(i - 1) }
        p.button("فتح الخبر في المتصفح") { openLink(n.link) }
        p.button("نسخ العنوان") { Ui.copy(this, n.title) }
        p.button("مشاركة الخبر") { Ui.share(this, n.title + "\n" + n.link) }
        if (n.full.isNotEmpty()) {
            p.section("نص الخبر")
            p.text(n.full, 18f)
        }
        p.bottomButton("رجوع للعناوين") {
            Speaker.stop()
            showHeadlines()
        }
        if (auto && Store.autoSpeak()) readNews(n, i)
    }

    // يقرأ العنوان ونص الخبر: لو متحمل يقرا فورًا، وإلا يحمّله (ويترجمه لو أجنبي) الأول
    private fun readNews(n: NewsItem, i: Int) {
        if (n.full.isNotEmpty()) {
            Speaker.stop()
            Speaker.speak(n.title + ".\n" + n.full)
            return
        }
        Speaker.stop()
        Http.cancelOwner(ownerTag)
        token++
        val my = token
        status?.text = "جاري تحميل الخبر، انتظر قليلًا"
        Http.thread(ownerTag) {
            // لو الخبر بيتحمل بالفعل في التحميل المسبق نستناه بدل ما نكرر الطلب
            var waited = 0
            while (n.loading && waited < 90000 && my == token) {
                try { Thread.sleep(200) } catch (e: Exception) { }
                waited += 200
            }
            if (my != token) return@thread
            if (n.full.isNotEmpty()) {
                val ready = n.full
                runOnUiThread {
                    if (my == token) {
                        openItem(i, false)
                        Speaker.speak(n.title + ".\n" + ready)
                    }
                }
                return@thread
            }
            n.loading = true
            try {
            var orig = n.fullOrig
            var reason = ""
            if (orig.isEmpty()) {
                val b = NewsArticle.load(n, ownerTag) { my != token }
                val t = b.text
                if (t != null) orig = t else reason = b.reason
            }
            var body: String? = null
            if (orig.isNotEmpty()) {
                n.fullOrig = orig
                if (n.foreign) {
                    runOnUiThread { if (my == token) status?.text = "جاري ترجمة نص الخبر للعربية، انتظر قليلًا" }
                    body = NewsTranslate.long(orig, ownerTag) { my != token } ?: orig
                } else {
                    body = orig
                }
            }
            // لو نص الخبر ما اتحملش: نقرأ الملخص (ونترجمه لو أجنبي)
            var summary = n.summary
            if (body == null && summary.isNotEmpty() && n.foreign && !n.sumTr && my == token) {
                summary = NewsTranslate.many(listOf(summary), ownerTag) { my != token }[0]
                n.summary = summary
                n.sumTr = true
            }
            runOnUiThread {
                if (my == token) {
                    val t = body
                    if (t != null) {
                        n.full = t
                        openItem(i, false)
                        Speaker.speak(n.title + ".\n" + t)
                    } else {
                        status?.text = reason
                        Speaker.speak(n.title + (if (summary.isNotEmpty()) ".\n" + summary else ""))
                    }
                }
            }
            } finally {
                n.loading = false
            }
        }
    }

    private fun adjustRate(d: Int) {
        var best = 0
        var bd = 99f
        for (k in rates.indices) {
            val diff = Math.abs(rates[k] - Store.rate())
            if (diff < bd) {
                bd = diff
                best = k
            }
        }
        val nk = if (best + d < 0) 0 else if (best + d >= rates.size) rates.size - 1 else best + d
        Speaker.setRate(rates[nk])
        Ui.toast(this, "سرعة القراءة: " + rateNames[nk] + ". بتتطبق من القراءة الجاية")
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Ui.toast(this, "تعذر فتح الرابط")
        }
    }

    // ---------- الإعدادات ----------
    private fun showSettings() {
        screen = 4
        stopPrefetch()
        cancelWork()
        Speaker.stop()
        val p = Page(this, "إعدادات قارئ الأخبار")
        val how = if (NewsData.isAuto(this)) "تلقائي (" + countryName() + ")" else countryName() + " (اخترتها بنفسك)"
        p.button("الدولة للأخبار المحلية: " + how) { chooseCountry() }
        p.button("ترجمة المصادر الأجنبية: " + (if (NewsData.translateOn(this)) "تشغيل" else "إيقاف")) {
            NewsData.setTranslate(this, !NewsData.translateOn(this))
            showSettings()
        }
        p.button("محرك الصوت") { chooseEngine() }
        var rn = rateNames[2]
        for (k in rates.indices) if (rates[k] == Store.rate()) rn = rateNames[k]
        p.button("سرعة القراءة: " + rn) { chooseRate() }
        p.button("اختبار الصوت") {
            Speaker.stop()
            Speaker.speak("هذا اختبار لقراءة الأخبار بالصوت والسرعة المختارين")
        }
        p.button("عرض آخر خطأ في تحميل الخبر") {
            val e = NewsArticle.lastError
            Ui.message(this, "آخر خطأ في تحميل الخبر", if (e.isEmpty()) "مفيش أخطاء" else e)
        }
        p.button("مساعدة: شرح قارئ الأخبار") { showHelp() }
        p.bottomButton("رجوع") { backToTop() }
    }

    private fun chooseCountry() {
        val labels = ArrayList<String>()
        labels.add("تلقائي (حسب الهاتف)")
        for (c in NewsData.ORDER) labels.add(NewsData.NAMES[c] ?: c)
        Ui.choose(this, "اختيار الدولة", labels) { i ->
            if (i == 0) NewsData.setAuto(this) else NewsData.setManual(this, NewsData.ORDER[i - 1])
            applyCountry()
            showSettings()
        }
    }

    private fun chooseEngine() {
        val engines = Speaker.engines()
        val labels = ArrayList<String>()
        labels.add("محرك النظام الافتراضي")
        for (e in engines) labels.add(e.label.toString())
        Ui.choose(this, "اختيار محرك الصوت", labels) { i ->
            if (i == 0) {
                Speaker.setEngine("")
                Ui.toast(this, "تم اختيار المحرك الافتراضي")
            } else {
                Speaker.setEngine(engines[i - 1].name)
                Ui.toast(this, "تم اختيار " + engines[i - 1].label.toString())
            }
        }
    }

    private fun chooseRate() {
        Ui.choose(this, "سرعة القراءة", rateNames) { i ->
            Speaker.setRate(rates[i])
            showSettings()
            Speaker.speak("هذه تجربة للسرعة الجديدة")
        }
    }

    // ---------- المساعدة ----------
    private fun showHelp() {
        screen = 5
        stopPrefetch()
        cancelWork()
        Speaker.stop()
        val p = Page(this, "شرح قارئ الأخبار")
        p.text("قارئ الأخبار بيجيب لك آخر الأخبار والصحف والمقالات من الإنترنت ويقراها لك بصوت محرك النطق اللي على تليفونك. مفيش مفاتيح مطلوبة.", 18f)
        p.section("الأقسام في الشاشة الرئيسية")
        p.text("أخبار محلية: أخبار دولتك. التطبيق بيتعرف على دولتك تلقائيًا من شبكة الهاتف أو الشريحة أو لغة الجهاز، وتقدر تختار دولة تانية من الإعدادات.", 18f)
        p.text("أخبار عربية، وأخبار دولية، والصحف المحلية والعربية والعالمية، والمقالات المحلية والعربية والعالمية.", 18f)
        p.text("المصادر الأجنبية بتتترجم للعربية تلقائيًا، وتقدر توقف الترجمة من الإعدادات.", 18f)
        p.section("جوه كل قسم")
        p.text("اختار التصنيف أو الجريدة. وبعدها: قراءة أول 10 عناوين، وإيقاف القراءة، وتحديث (العناوين بتتخزن 10 دقايق عشان تفتح بسرعة). دوس على أي عنوان عشان تفتح تفاصيل الخبر.", 18f)
        p.section("شاشة تفاصيل الخبر")
        p.text("قراءة الخبر: بتقرا العنوان ونص الخبر (بيتحمل ويتترجم لو لازم). قراءة العنوان فقط. إيقاف القراءة. زيادة وتقليل سرعة القراءة. الخبر التالي والسابق. فتح الخبر في المتصفح، ونسخ العنوان ومشاركته.", 18f)
        p.section("ملاحظات")
        p.text("بعض الجرايد بتمنع قراءة الخبر كامل، فبتتقرا العناوين والملخص بس. تقدر تشوف السبب من الإعدادات، زر عرض آخر خطأ في تحميل الخبر. لو مفيش صوت عربي، اختار محرك نطق فيه عربي من الإعدادات.", 18f)
        p.bottomButton("رجوع") { backToTop() }
    }

    // الرجوع يطلع مستوى واحد، ولا يخرج من القارئ كله إلا من الشاشة الرئيسية
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            3 -> {
                Speaker.stop()
                showHeadlines()
            }
            2 -> openSection(si)
            1, 4, 5 -> backToTop()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        token++
        Http.cancelOwner(ownerTag)
        stopPrefetch()
        Speaker.stop()
        super.onDestroy()
    }
}
