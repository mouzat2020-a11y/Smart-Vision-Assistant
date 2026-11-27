package com.mostafa.smartvision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.TextView
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// خبر واحد
class NewsItem(val title: String, val source: String, val link: String, val date: Long, val summary: String) {
    // نص الخبر الكامل بعد تحميله من موقع الجريدة (فاضي لحد ما يتحمل)
    var full: String = ""
    // للأخبار الأجنبية بعد ترجمتها للعربية: العنوان الأصلي
    var foreign: Boolean = false
    var origTitle: String = ""
}

// قسم أخبار. kind: 0 = آخر أخبار الدولة، 1 = قسم جاهز من جوجل نيوز، 2 = بحث، 3 = أخبار عربية، 4 = أخبار عالمية
class NewsSection(val name: String, val kind: Int, val topic: String, val query: String)

class NewsResult(val items: List<NewsItem>, val error: String?)

object NewsData {
    val ORDER = listOf("EG", "SA", "AE", "DZ", "MA", "TN", "LY", "JO", "LB", "SY", "IQ", "KW", "QA", "BH", "OM", "YE", "PS", "SD", "MR")
    val NAMES = mapOf(
        "EG" to "مصر", "SA" to "السعودية", "AE" to "الإمارات", "DZ" to "الجزائر", "MA" to "المغرب",
        "TN" to "تونس", "LY" to "ليبيا", "JO" to "الأردن", "LB" to "لبنان", "SY" to "سوريا",
        "IQ" to "العراق", "KW" to "الكويت", "QA" to "قطر", "BH" to "البحرين", "OM" to "سلطنة عمان",
        "YE" to "اليمن", "PS" to "فلسطين", "SD" to "السودان", "MR" to "موريتانيا"
    )

    val LOCAL = listOf(
        NewsSection("آخر الأخبار", 0, "", ""),
        NewsSection("سياسة", 1, "NATION", "سياسة OR الحكومة OR الرئيس OR البرلمان OR وزير OR مجلس"),
        NewsSection("فن", 1, "ENTERTAINMENT", "فنان OR فنانة OR مسلسل OR فيلم OR حفل OR مهرجان OR سينما OR ممثل OR مطرب"),
        NewsSection("رياضة", 1, "SPORTS", "كرة القدم OR الدوري OR مباراة OR المنتخب OR لاعب OR كأس"),
        NewsSection("اقتصاد", 1, "BUSINESS", "اقتصاد OR البورصة OR الدولار OR الأسعار OR الاستثمار OR البنك"),
        NewsSection("تقنية", 1, "TECHNOLOGY", "تكنولوجيا OR تقنية OR هاتف OR الذكاء الاصطناعي OR تطبيق"),
        NewsSection("صحة", 1, "HEALTH", "صحة OR مرض OR علاج OR مستشفى OR وزارة الصحة OR دواء"),
        NewsSection("حوادث", 2, "", "حادث OR حريق OR مصرع OR إصابة OR القبض OR جريمة OR انقلاب")
    )
    val OTHER = listOf(
        NewsSection("أخبار عربية", 3, "", ""),
        NewsSection("أخبار عالمية", 4, "", "")
    )

    private const val MAX_ITEMS = 40
    private const val MAX_AGE_MS = 30L * 24L * 3600L * 1000L

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun gnSearch(q: String, gl: String, w: String): String {
        return "https://news.google.com/rss/search?q=" + enc(q + " when:" + w) + "&hl=ar&gl=" + gl + "&ceid=" + gl + ":ar"
    }

    private fun gnTopic(topic: String, gl: String): String {
        return "https://news.google.com/rss/headlines/section/topic/" + topic + "?hl=ar&gl=" + gl + "&ceid=" + gl + ":ar"
    }

    // الروابط الأساسية، وروابط احتياطية تُجرَّب لو الأساسية ما رجّعتش أخبارًا
    fun urlsFor(sec: NewsSection, cc: String): Pair<List<String>, List<String>> {
        val name = NAMES[cc] ?: "مصر"
        return when (sec.kind) {
            0 -> Pair(
                listOf("https://news.google.com/rss?hl=ar&gl=" + cc + "&ceid=" + cc + ":ar"),
                listOf(gnSearch(name, "SA", "7d"))
            )
            1 -> Pair(
                listOf(gnTopic(sec.topic, cc)),
                listOf(gnSearch(sec.query, cc, "7d"), gnSearch(name + " (" + sec.query + ")", "SA", "7d"))
            )
            2 -> Pair(
                listOf(gnSearch(sec.query, cc, "7d")),
                listOf(gnSearch(name + " (" + sec.query + ")", "SA", "7d"))
            )
            3 -> Pair(
                listOf(
                    "https://feeds.bbci.co.uk/arabic/rss.xml",
                    "https://www.skynewsarabia.com/web/rss",
                    "https://www.alarabiya.net/feed/rss2/ar.xml",
                    "https://aawsat.com/feed"
                ),
                listOf(gnTopic("WORLD", "SA"))
            )
            else -> Pair(
                listOf(
                    gnTopic("WORLD", "US"),
                    "https://rss.dw.com/xml/rss-ar-all",
                    "https://www.france24.com/ar/rss"
                ),
                listOf(gnSearch("أخبار العالم", "SA", "3d"))
            )
        }
    }

    // ---------- الدولة ----------
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
    fun load(sec: NewsSection, cc: String, ownerTag: String, cancel: () -> Boolean): NewsResult {
        val urls = urlsFor(sec, cc)
        return loadUrls(urls.first, urls.second, ownerTag, cancel)
    }

    fun loadUrls(primary: List<String>, fallback: List<String>, ownerTag: String, cancel: () -> Boolean): NewsResult {
        var items = fetchAll(primary, ownerTag)
        if (items.isEmpty() && !cancel() && fallback.isNotEmpty()) items = fetchAll(fallback, ownerTag)
        if (cancel()) return NewsResult(emptyList(), "تم الإيقاف")
        if (items.isEmpty()) {
            val msg = if (!Net.isOnline()) "مفيش اتصال بالإنترنت" else "ما لقيتش أخبارًا في القسم ده دلوقتي. جرّب تحديثًا بعد شوية"
            return NewsResult(emptyList(), msg)
        }
        return NewsResult(items, null)
    }

    // يجلب عدة خلاصات في نفس الوقت، ثم يدمجها ويرتبها من الأحدث
    private fun fetchAll(urls: List<String>, ownerTag: String): List<NewsItem> {
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
        return if (merged.size > MAX_ITEMS) ArrayList(merged.subList(0, MAX_ITEMS)) else merged
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

class NewsActivity : Activity() {
    // علامة هذه الشاشة: إلغاء طلباتها لا يقطع طلبات شاشة أخرى
    private val ownerTag = "news" + System.identityHashCode(this)
    private var token = 0
    private var screen = 0 // 0 الأقسام، 1 العناوين، 2 تفاصيل الخبر
    private var cc = "EG"
    private var sec: NewsSection = NewsData.LOCAL[0]
    private var items: List<NewsItem> = emptyList()
    private var idx = 0
    private var status: TextView? = null
    private var src: NewsSource? = null
    private var group: NewsGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Speaker.init(this)
        cc = NewsData.activeCountry(this)
        showSections()
    }

    private fun cancelWork() {
        token++
        Http.cancelOwner(ownerTag)
    }

    private fun countryName(): String = NewsData.NAMES[cc] ?: ""

    // ---------- شاشة الأقسام ----------
    private fun showSections() {
        screen = 0
        src = null
        group = null
        cancelWork()
        Speaker.stop()
        val p = Page(this, "قارئ الأخبار")
        val how = if (NewsData.isAuto(this)) " (تلقائي حسب الهاتف)" else " (من اختيارك)"
        p.label("الدولة الحالية: " + countryName() + how)
        p.button("تغيير الدولة") { chooseCountry() }
        p.section("أخبار " + countryName())
        for (s in NewsData.LOCAL) {
            p.button(s.name) { openSection(s) }
        }
        p.section("أخبار عربية وعالمية")
        for (s in NewsData.OTHER) {
            p.button(s.name) { openSection(s) }
        }
        p.section("الصحف")
        p.button("الصحف المحلية (" + countryName() + ")") { openGroup(NewsSources.localPapers(cc)) }
        p.button("الصحف العربية") { openGroup(NewsSources.arabPapers()) }
        p.button("الصحف العالمية") { openGroup(NewsSources.worldPapers()) }
        p.section("المقالات")
        p.button("المقالات المحلية (" + countryName() + ")") { openGroup(NewsSources.localArticles(cc)) }
        p.button("المقالات العربية") { openGroup(NewsSources.arabArticles()) }
        p.button("المقالات العالمية") { openGroup(NewsSources.worldArticles()) }
        p.bottomButton("رجوع للقائمة الرئيسية") { finish() }
    }

    // ---------- قائمة مصادر (صحف أو مقالات) ----------
    private fun openGroup(g: NewsGroup) {
        group = g
        showGroup()
    }

    private fun showGroup() {
        val g = group ?: return
        screen = 3
        src = null
        cancelWork()
        Speaker.stop()
        val p = Page(this, g.title)
        if (g.hasForeign) p.label("بعض المصادر بلغات أجنبية، وبتترجم للعربية تلقائيًا (الترجمة آلية وقد تكون غير دقيقة)")
        for (s in g.sources) {
            p.button(s.name) { openSource(s) }
        }
        p.bottomButton("رجوع للأقسام") { showSections() }
    }

    private fun openSource(s: NewsSource) {
        src = s
        loadSection()
    }

    private fun backFromList() {
        if (group != null) showGroup() else showSections()
    }

    private fun chooseCountry() {
        val labels = ArrayList<String>()
        labels.add("تلقائي (حسب الهاتف)")
        for (c in NewsData.ORDER) labels.add(NewsData.NAMES[c] ?: c)
        Ui.choose(this, "اختيار الدولة", labels) { i ->
            if (i == 0) NewsData.setAuto(this) else NewsData.setManual(this, NewsData.ORDER[i - 1])
            cc = NewsData.activeCountry(this)
            showSections()
        }
    }

    // ---------- شاشة العناوين ----------
    private fun openSection(s: NewsSection) {
        sec = s
        src = null
        loadSection()
    }

    private fun sectionTitle(): String {
        val s = src
        if (s != null) return s.name
        return if (sec.kind <= 2) sec.name + " - " + countryName() else sec.name
    }

    private fun loadSection() {
        screen = 1
        cancelWork()
        Speaker.stop()
        val my = token
        val p = Page(this, sectionTitle())
        val st = p.label("جاري تحميل الأخبار، انتظر قليلًا")
        Ui.live(st)
        p.bottomButton(if (group != null) "رجوع للمصادر" else "رجوع للأقسام") { backFromList() }
        val source = src
        Http.thread(ownerTag) {
            val res = if (source != null) {
                NewsData.loadUrls(source.urls, source.fallback, ownerTag) { my != token }
            } else {
                NewsData.load(sec, cc, ownerTag) { my != token }
            }
            var list = res.items
            if (source != null && source.foreign && list.isNotEmpty() && my == token) {
                runOnUiThread { if (my == token) st.text = "جاري ترجمة العناوين للعربية، انتظر قليلًا" }
                list = NewsTranslate.items(list) { my != token }
            }
            runOnUiThread {
                if (my == token) {
                    items = list
                    showHeadlines(res.error)
                }
            }
        }
    }

    private fun showHeadlines(err: String?) {
        screen = 1
        cancelWork()
        val p = Page(this, sectionTitle())
        if (items.isEmpty()) {
            val st = p.label(err ?: "لا توجد أخبار دلوقتي")
            Ui.live(st)
            p.button("إعادة المحاولة") { loadSection() }
        } else {
            val st = p.label("تم تحميل " + items.size + " خبرًا")
            Ui.live(st)
            p.button("تحديث الأخبار") { loadSection() }
            p.button("قراءة العناوين بصوت عالٍ / إيقاف") { toggleHeadlines() }
            for (i in items.indices) {
                val n = items[i]
                val a = NewsData.ago(n.date)
                val label = n.title + " — " + n.source + (if (a.isNotEmpty()) " — " + a else "")
                p.button(label) { openItem(i) }
            }
        }
        p.bottomButton(if (group != null) "رجوع للمصادر" else "رجوع للأقسام") { backFromList() }
        p.root.announceForAccessibility(if (items.isEmpty()) (err ?: "لا توجد أخبار") else "تم تحميل " + items.size + " خبرًا")
    }

    private fun toggleHeadlines() {
        if (Speaker.isSpeaking()) {
            Speaker.stop()
            return
        }
        val sb = StringBuilder()
        val n = if (items.size < 10) items.size else 10
        for (i in 0 until n) {
            sb.append(i + 1).append(". ").append(items[i].title).append(".\n")
        }
        Speaker.speak(sb.toString())
    }

    // ---------- شاشة تفاصيل الخبر ----------
    private fun readText(n: NewsItem): String {
        val sb = StringBuilder()
        sb.append(n.title).append(".\n")
        if (n.summary.isNotEmpty()) sb.append(n.summary).append("\n")
        if (n.source.isNotEmpty()) sb.append("المصدر: ").append(n.source).append(".")
        return sb.toString()
    }

    private fun openItem(i: Int, auto: Boolean = true) {
        if (i < 0 || i >= items.size) return
        idx = i
        screen = 2
        cancelWork()
        Speaker.stop()
        val n = items[i]
        val p = Page(this, "تفاصيل الخبر")
        p.text(n.title, 22f)
        val a = NewsData.ago(n.date)
        p.label(n.source + (if (a.isNotEmpty()) " — " + a else ""))
        if (n.foreign && n.origTitle.isNotEmpty()) p.label("العنوان الأصلي: " + n.origTitle)
        if (n.summary.isNotEmpty()) p.text(n.summary, 18f)
        val st = p.label("")
        Ui.live(st)
        status = st
        p.button("قراءة الخبر بصوت عالٍ / إيقاف") {
            if (Speaker.isSpeaking()) Speaker.stop() else Speaker.speak(readText(n))
        }
        p.button(if (n.full.isEmpty()) "تحميل وقراءة نص الخبر كاملًا" else "قراءة نص الخبر كاملًا / إيقاف") { readFull(n, i) }
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
            showHeadlines(null)
        }
        if (auto && Store.autoSpeak()) Speaker.speak(readText(n))
    }

    // يقرأ نص الخبر كاملًا: لو متحمل نبدأ القراءة، وإلا نحمّله أولًا
    private fun readFull(n: NewsItem, i: Int) {
        if (n.full.isNotEmpty()) {
            if (Speaker.isSpeaking()) Speaker.stop() else Speaker.speak(n.title + ".\n" + n.full)
            return
        }
        Speaker.stop()
        Http.cancelOwner(ownerTag)
        token++
        val my = token
        status?.text = "جاري تحميل نص الخبر كاملًا، انتظر قليلًا"
        Http.thread(ownerTag) {
            val b = NewsArticle.load(n, ownerTag) { my != token }
            var body = b.text
            if (body != null && n.foreign && my == token) {
                runOnUiThread { if (my == token) status?.text = "جاري ترجمة نص الخبر للعربية، انتظر قليلًا" }
                body = NewsTranslate.long(body) { my != token } ?: body
            }
            runOnUiThread {
                if (my == token) {
                    val t = body
                    if (t != null) {
                        n.full = t
                        openItem(i, false)
                        Speaker.speak(n.title + ".\n" + t)
                    } else {
                        status?.text = b.reason
                    }
                }
            }
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Ui.toast(this, "تعذر فتح الرابط")
        }
    }

    // الرجوع يطلع مستوى واحد، ولا يخرج من القارئ كله إلا من شاشة الأقسام
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen == 2) {
            Speaker.stop()
            showHeadlines(null)
        } else if (screen == 1) {
            backFromList()
        } else if (screen == 3) {
            showSections()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        token++
        Http.cancelOwner(ownerTag)
        Speaker.stop()
        super.onDestroy()
    }
}
